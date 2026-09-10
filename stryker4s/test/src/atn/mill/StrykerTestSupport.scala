package atn.mill

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import mill.api.ExecResult
import mill.testkit.UnitTester
import mutationtesting.{Location, MutantResult, Position}
import stryker4s.log.{Level, Logger}
import stryker4s.model.{InitialTestRunResult, MutantId, MutantMetadata, MutantWithId, MutatedCode}
import stryker4s.run.TestRunner
import stryker4s.testrunner.api.*

import java.net.{StandardProtocolFamily, UnixDomainSocketAddress}
import java.nio.channels.{Channels, ServerSocketChannel}
import java.util.concurrent.atomic.AtomicReference
import scala.meta.Term
import scala.util.control.NonFatal

/**
 * Fixtures the stryker4s runner tests share: a silent logger, the test JVM's own classpath (what a forked server or an
 * instrumented compile runs against), utest test groups, mutants, and a scripted stand-in for the forked server.
 */
object StrykerTestSupport:

  /** Instrumentation, rollback and the runners only log progress; nothing in the tests needs to see it. */
  given silentLogger: Logger = new Logger:
    def log(level: Level, msg: => String): Unit                  = ()
    def log(level: Level, msg: => String, t: => Throwable): Unit = ()

  /** A logger that keeps what was logged, for the tests that check what a runner reported doing. */
  final class RecordingLogger extends Logger:
    private val entries                                          = new AtomicReference(Vector.empty[(Level, String)])
    def log(level: Level, msg: => String): Unit                  = entries.updateAndGet(_ :+ (level, msg))
    def log(level: Level, msg: => String, t: => Throwable): Unit = log(level, msg)
    def messages: Vector[String]                                 = entries.get.map(_._2)

  /** Run an effect to completion on the calling thread. */
  def run[A](io: IO[A]): A = io.unsafeRunSync()

  /**
   * The classpath of this test JVM. Mill's test worker loads the suites through a URLClassLoader over the module's
   * `runClasspath` (the `testargs` file it reads), so `java.class.path` alone does not list it.
   */
  val testClasspath: Seq[os.Path] =
    val fromLoaders  = Iterator
      .iterate(getClass.getClassLoader)(_.getParent)
      .takeWhile(_ != null)
      .collect { case loader: java.net.URLClassLoader => loader.getURLs.toSeq }
      .flatten
      .map(url => os.Path(java.nio.file.Path.of(url.toURI)))
    val fromProperty = sys
      .props("java.class.path")
      .split(java.io.File.pathSeparator)
      .iterator
      .filter(_.nonEmpty)
      .map(os.Path(_, os.pwd))
    (fromLoaders ++ fromProperty).toSeq.distinct

  val utestFramework: String = "utest.runner.Framework"

  /** The fingerprint utest's framework reports for a suite object. */
  val utestFingerprint: Fingerprint =
    SubclassFingerprint(isModule = true, superclassName = "utest.TestSuite", requireNoArgConstructor = true)

  /** The test groups a forked server runs: `suites` as utest suite objects, each selected whole. */
  def utestGroups(suites: String*): Seq[TestGroup] =
    val taskDefs =
      suites.map(TaskDefinition(_, utestFingerprint, explicitlySpecified = false, selectors = Seq(SuiteSelector())))
    Seq(TestGroup(utestFramework, taskDefs, Some(RunnerOptions(Seq.empty, Seq.empty))))

  /** A mutant with `id`, as stryker4s hands them to a test runner; the runner only ever looks at the id. */
  def mutant(id: Int): MutantWithId =
    val metadata = MutantMetadata("a", "b", "TestMutator", Location(Position(1, 1), Position(1, 2)), None)
    MutantWithId(MutantId(id), MutatedCode(Term.Name("b"), metadata))

  /** The definition ids of `files`, in the form [[MillProcessTestRunner]] reports `coveredBy` and `killedBy`. */
  def definitionIds(files: Seq[TestFile]): Seq[String] = files.flatMap(_.definitions).map(_.id.toString)

  /** What stryker4s does with a runner: the initial run, then each of the mutants `ids` against the tests it found. */
  def initialThenMutants(runner: TestRunner, ids: List[Int]): IO[(InitialTestRunResult, List[MutantResult])] =
    runner.initialTestRun().flatMap { initial =>
      ids.traverse(id => runner.runMutant(mutant(id), initial.testNames)).tupleLeft(initial)
    }

  /** Evaluate `selector` with `eval`, failing with the evaluation's own diagnostics rather than an opaque `Left`. */
  def evalOrFail(eval: UnitTester, selector: String): UnitTester.Result[Seq[?]] =
    eval(selector) match
      case Right(result)                          => result
      case Left(ExecResult.Exception(t, _))       => throw new AssertionError(s"$selector failed", t)
      case Left(ExecResult.Failure(msg, failure)) => throw new AssertionError(s"$selector failed: $msg $failure")

  /**
   * A stand-in for the forked `SbtTestRunnerMain` server: listens on a fresh unix socket and answers the `n`-th request
   * with `script(n, request)`, or hangs up when that is `None`. Serves one client, in a daemon thread, for the duration
   * of `body`.
   */
  def withFakeServer[A](script: (Int, Request) => Option[Response])(body: os.Path => A): A =
    val socketPath = os.temp(prefix = "s4s-fake-", suffix = ".sock")
    os.remove(socketPath)
    val server     = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
    server.bind(UnixDomainSocketAddress.of(socketPath.toNIO))
    val thread     = new Thread(() => serve(server, script))
    thread.setDaemon(true)
    thread.start()
    try body(socketPath)
    finally
      server.close()
      os.remove.all(socketPath)

  private def serve(server: ServerSocketChannel, script: (Int, Request) => Option[Response]): Unit =
    val client = server.accept()
    try
      val in  = Channels.newInputStream(client)
      val out = Channels.newOutputStream(client)
      Iterator
        .continually(RequestMessage.parseDelimitedFrom(in))
        .takeWhile(_.isDefined)
        .flatten
        .zipWithIndex
        .foreach { (message, n) =>
          script(n, message.toRequest) match
            case Some(response) =>
              response.asMessage.writeDelimitedTo(out)
              out.flush()
            case None           => client.close()
        }
    catch case NonFatal(_) => ()
    finally client.close()

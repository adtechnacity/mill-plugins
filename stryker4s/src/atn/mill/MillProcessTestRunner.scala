package atn.mill

import cats.data.NonEmptyList
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import fs2.io.net.{Network, Socket}
import fs2.io.{readOutputStream, toInputStreamResource}

import com.comcast.ip4s.UnixSocketAddress
import com.google.protobuf.CodedOutputStream
import mutationtesting.{MutantResult, MutantStatus}
import scalapb.LiteParser
import stryker4s.config.Config
import stryker4s.log.Logger
import stryker4s.model.*
import stryker4s.run.TestRunner
import stryker4s.testrunner.api.*

import java.io.InputStream
import java.net.{ConnectException, SocketException}
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.*

/**
 * Socket connection to a forked `stryker4s-sbt-testrunner` server process: length-delimited protobuf
 * [[Request]]/[[Response]] messages over a unix domain socket.
 *
 * Port of stryker4s' sbt-plugin `SocketTestRunnerConnection` (Apache-2.0), which lives in the Scala 2.12 sbt-plugin
 * artifact and can't be used from Mill directly.
 */
final class MillTestRunnerConnection(socket: Socket[IO], input: InputStream)(using log: Logger):

  def sendMessage(request: Request): IO[Response] =
    (write(request.asMessage) *> read)
      .flatTap(response => IO(log.debug(s"Received message $response")))

  private def write(msg: RequestMessage): IO[Unit] =
    readOutputStream(bufferSizeForMsg(msg))(os => IO.blocking(msg.writeDelimitedTo(os)))
      .through(socket.writes)
      .compile
      .drain

  private def read: IO[Response] = IO.interruptible(ResponseMessage.parseDelimitedFrom(input)).flatMap {
    case Some(responseMsg) => IO.pure(responseMsg.toResponse)
    case None              => IO.raiseError(new RuntimeException("Failed to parse ResponseMessage from input stream"))
  }

  /** Copied from RequestMessage#writeDelimitedTo. */
  private def bufferSizeForMsg(msg: RequestMessage): Int =
    val serialized = msg.serializedSize
    LiteParser.preferredCodedOutputStreamBufferSize(CodedOutputStream.computeUInt32SizeNoTag(serialized) + serialized)

object MillTestRunnerConnection:

  def create(socketPath: os.Path)(using Logger): Resource[IO, MillTestRunnerConnection] = for
    socket <- Network[IO].connect(UnixSocketAddress(socketPath.toString))
    input  <- toInputStreamResource(socket.reads)
  yield new MillTestRunnerConnection(socket, input)

/**
 * Mill port of stryker4s' sbt-plugin `ProcessTestRunner` (Apache-2.0): drives one long-lived forked `SbtTestRunnerMain`
 * server over a socket. The server activates mutations in-process (no JVM start per mutant), runs ONLY the tests
 * covering each mutant, fails early on the first kill, and collects per-test coverage during the initial run — which is
 * what lets stryker4s core hand each mutant its covering tests.
 */
class MillProcessTestRunner(testProcess: MillTestRunnerConnection) extends TestRunner:

  override def runMutant(mutant: MutantWithId, testsToRun: Seq[TestFile]): IO[MutantResult] =
    val message   = StartTestRun.of(mutant.id, testsToRun.map(_.fullyQualifiedName))
    val coveredBy = testsToRun.flatMap(_.definitions).map(_.id).some

    testProcess.sendMessage(message).map {
      case TestsSuccessful(testsCompleted)                =>
        mutant.toMutantResult(MutantStatus.Survived, testsCompleted = testsCompleted.some, coveredBy = coveredBy)
      case TestsUnsuccessful(testsCompleted, failedTests) =>
        mutant.toMutantResult(
          MutantStatus.Killed,
          testsCompleted = testsCompleted.some,
          coveredBy = coveredBy,
          killedBy = extractKilledBy(testsToRun, failedTests).some,
          statusReason = extractStatusReason(failedTests)
        )
      case ErrorDuringTestRun(msg)                        =>
        mutant.toMutantResult(MutantStatus.Killed, statusReason = msg.some, coveredBy = coveredBy)
      case _                                              => mutant.toMutantResult(MutantStatus.RuntimeError, coveredBy = coveredBy)
    }

  /** Map failed test names to their corresponding test ids. */
  private def extractKilledBy(
    testsToRun: Seq[TestFile],
    failedTests: Seq[FailedTestDefinition]
  ): Seq[TestDefinitionId] =
    failedTests.flatMap { failedTest =>
      testsToRun
        .find(t => failedTest.fullyQualifiedName.contains(t.fullyQualifiedName))
        .flatMap(_.definitions.find(_.name == failedTest.name).map(_.id))
    }

  /** Extract the status reason from the failed tests into a single string (or None). */
  private def extractStatusReason(failedTests: Seq[FailedTestDefinition]): Option[String] =
    NonEmptyList
      .fromList(failedTests.flatMap(test => test.message.map(msg => s"${test.name}: $msg")).toList)
      .map(_.mkString_("\n\n"))

  /**
   * Initial test-run is done twice, so static mutants can be filtered out: a mutant whose code only runs during
   * class/object initialization has coverage in the first run but not the second (the JVM initializes once), and
   * mutation switching can never exercise it.
   *
   * @see
   *   https://github.com/stryker-mutator/stryker4s/pull/565#issuecomment-688438699
   */
  override def initialTestRun(): IO[InitialTestRunResult] =
    val initialTestRun = testProcess.sendMessage(StartInitialTestRun())

    initialTestRun.map2(initialTestRun) {
      case (firstRun: CoverageTestRunResult, secondRun: CoverageTestRunResult) =>
        val averageDuration =
          FiniteDuration((firstRun.durationNanos + secondRun.durationNanos) / 2, TimeUnit.NANOSECONDS)

        InitialTestRunCoverageReport(
          firstRun.isSuccessful && secondRun.isSuccessful,
          CoverageReport(firstRun.getCoverageTestNameMap),
          CoverageReport(secondRun.getCoverageTestNameMap),
          averageDuration,
          firstRun.getCoverageTestNameMap.testNameIds.values.toSeq
        )
      case x                                                                   => throw new MatchError(x)
    }

object MillProcessTestRunner:

  private val classPathSeparator = java.io.File.pathSeparator

  /**
   * Fork one `SbtTestRunnerMain` server on `classpath`, connect to it over a fresh unix socket, and hand it the test
   * context. The returned runner is wrapped by the caller with stryker4s core's timeout/retry decorators.
   */
  def newProcess(classpath: Seq[os.Path], javaOpts: Seq[String], testGroups: Seq[TestGroup], workingDir: os.Path)(using
    config: Config,
    log: Logger
  ): Resource[IO, TestRunner] =
    for
      socketPath <- Resource.eval(IO.blocking {
                      // Create and delete a temp file, so a known-unique free path exists for the server's socket.
                      val f = os.temp(prefix = "s4s-", suffix = ".sock")
                      os.remove(f)
                      f
                    })
      _          <- createProcess(classpath, javaOpts, socketPath, workingDir)
      conn       <- connectWithBackoff(socketPath)
      _          <- Resource.eval(conn.sendMessage(TestProcessContext(testGroups)).void)
    yield new MillProcessTestRunner(conn)

  private def createProcess(classpath: Seq[os.Path], javaOpts: Seq[String], socketPath: os.Path, workingDir: os.Path)(
    using log: Logger
  ): Resource[IO, os.SubProcess] =
    val javaBin = os.Path(sys.props("java.home")) / "bin" / "java"
    val allArgs = List("-cp", classpath.map(_.toString).mkString(classPathSeparator)) ++
      javaOpts ++
      List(s"-D${TestProcessProperties.unixSocketPath}=$socketPath", "stryker4s.sbt.testrunner.SbtTestRunnerMain")

    Resource.make(IO.blocking {
      // The classpath is routinely too long for the OS argument limit — pass everything via a java @argfile.
      val argFile   = os.temp(allArgs.map(quoteArg).mkString(" "), prefix = "s4s-args-")
      log.debug(s"Starting testrunner process '$javaBin @$argFile'")
      // Server output goes to a log file, NEVER an inherited pipe: the initial run streams the whole suite's test
      // output, and a pipe with no active reader would fill up and block the server (and with it the whole run).
      val serverLog = workingDir / s"testrunner-${ProcessHandle.current().pid()}-${System.nanoTime()}.log"
      os.proc(javaBin.toString, s"@$argFile").spawn(cwd = workingDir, stdout = serverLog, mergeErrIntoOut = true)
    })(process => IO.blocking(process.destroyForcibly()))

  /** Quote one @argfile token per the java launcher's argfile syntax (backslashes escaped, wrapped in quotes). */
  private def quoteArg(arg: String): String =
    "\"" + arg.replace("\\", "\\\\") + "\""

  private def connectWithBackoff(socketPath: os.Path)(using log: Logger): Resource[IO, MillTestRunnerConnection] =
    def retry(attemptsLeft: Int, delay: FiniteDuration): Resource[IO, MillTestRunnerConnection] =
      MillTestRunnerConnection.create(socketPath).handleErrorWith[MillTestRunnerConnection] {
        case _: ConnectException | _: SocketException | _: java.nio.file.NoSuchFileException if attemptsLeft > 0 =>
          Resource.eval(IO(log.debug(s"Could not connect to testprocess. Retrying after $delay...")) *> IO.sleep(delay))
            *> retry(attemptsLeft - 1, delay * 2)
        case e                                                                                                   =>
          Resource.raiseError[IO, MillTestRunnerConnection, Throwable](
            new RuntimeException("Could not connect to testprocess", e)
          )
      }
    retry(6, 0.2.seconds)

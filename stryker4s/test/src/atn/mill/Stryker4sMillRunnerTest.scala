package atn.mill

import cats.data.NonEmptyList
import mutationtesting.MutantStatus
import stryker4s.config.Config
import stryker4s.model.{CompilerErrMsg, MutantId}
import stryker4s.mutants.applymutants.ActiveMutationContext
import utest.*

import scala.meta.{dialects, Dialect, XtensionSyntax}

import StrykerTestSupport.{*, given}

object Stryker4sMillRunnerTest extends TestSuite:

  /**
   * The Scala version of this JVM's stdlib: Mill resolved that compiler to build the module, so coursier serves it from
   * its cache.
   */
  private val scalaVersion = scala.util.Properties.versionNumberString

  /** The scalac options the runner must drop: each would fail the compile of [[instrumentedSuite]] or leave traces. */
  private val droppedOptions =
    Seq("-Xfatal-warnings", "-Yexplicit-nulls", "-Xplugin:nope.jar", "-P:nope:x", "-Xsemanticdb", "-Wunused:all")

  /**
   * A suite as stryker4s's testrunner instrumenter writes it: coverage calls and a switch on the active mutant. Mutant
   * 0 keeps `positive(1)` true and survives; mutant 1 flips it and is killed. The non-exhaustive match warns by default
   * and the null is a type error under explicit nulls: fatal only if the runner kept those options.
   */
  private val instrumentedSuite =
    """package s4s
      |
      |import utest.*
      |
      |object MutatedSuite extends TestSuite:
      |  def positive(x: Int): Boolean =
      |    _root_.stryker4s.coverage.coverMutant(0, 1)
      |    _root_.stryker4s.activeMutation match
      |      case 0 => x >= 0
      |      case 1 => x < 0
      |      case _ => x > 0
      |  def first(o: Option[Int]): Int = o match
      |    case Some(v) => v
      |  val absent: String = null
      |  val tests = Tests:
      |    test("one is positive") {
      |      assert(positive(1))
      |    }
      |""".stripMargin

  /**
   * A workspace with one module `m` and a stryker4s tmp dir laid out as `MutantRunner.prepareEnv` does: the module's
   * sources, instrumented, under their workspace-relative path.
   */
  final private class Workspace(val root: os.Path, val logger: RecordingLogger = new RecordingLogger):
    val moduleSrc: os.Path = root / "m" / "src"
    val tmpDir: os.Path    = root / "target" / "stryker4s-1"
    val classDir: os.Path  = tmpDir / "classes"
    val logDir: os.Path    = root / "logs"

    /** Write `source` as a file of the module inside the tmp dir. */
    def write(name: String, source: String): Unit =
      os.write(tmpDir / "m" / "src" / "s4s" / name, source, createFolders = true)

    given Config = Config.default.copy(baseDir = fs2.io.file.Path(root.toString))

    def runner(scalacOptions: Seq[String] = Seq.empty, concurrency: Int = 1): Stryker4sMillRunner =
      new Stryker4sMillRunner(
        testClasspath = testClasspath,
        frameworkName = utestFramework,
        testClasses = Seq("s4s.MutatedSuite"),
        concurrency = concurrency,
        scalaVersion = scalaVersion,
        // The second root has no sources in the tmp dir: stryker4s only copies what exists.
        moduleSourceDirs = Seq(moduleSrc, root / "m" / "gen"),
        scalacOptions = scalacOptions,
        testRunnerJavaOpts = Seq("-Xmx512m"),
        testRunnerLogDir = Some(logDir)
      )(using logger)

    def resolve(scalacOptions: Seq[String] = Seq.empty, concurrency: Int = 1) =
      runner(scalacOptions, concurrency).resolveTestRunners(fs2.io.file.Path(tmpDir.toString))

  private def workspace(): Workspace = new Workspace(os.temp.dir())

  val tests = Tests:

    test("resolveTestRunners - compiles the instrumented sources and forks servers that run them mutated") {
      val ws                 = workspace()
      ws.write("MutatedSuite.scala", instrumentedSuite)
      ws.write("NOTES.md", "not a source")
      val Right(runners)     = ws.resolve(droppedOptions :+ "-deprecation", concurrency = 2): @unchecked
      assert(runners.size == 2)
      assert(os.exists(ws.classDir / "s4s" / "MutatedSuite.class"))
      // -Xsemanticdb was dropped, so no semanticdb output sits next to the classes.
      assert(!os.exists(ws.classDir / "META-INF" / "semanticdb"))
      val (initial, results) = run(runners.head.use(initialThenMutants(_, List(0, 1))))
      assert(initial.isSuccessful)
      assert(initial.coveredMutants.keySet == Set(MutantId(0), MutantId(1)))
      assert(results.map(_.status) == List(MutantStatus.Survived, MutantStatus.Killed))
      assert(os.list(ws.logDir).count(_.ext == "log") == 1)
      assert(ws.logger.messages.contains("Compiling 1 instrumented source file(s)..."))
      assert(ws.logger.messages.contains(s"Compiled instrumented sources to ${ws.classDir}"))
    }

    test("resolveTestRunners - compile errors come back per error, the path relative to the tmp dir, for rollback") {
      val ws           = workspace()
      ws.write("Broken.scala", "package s4s\n\nobject Broken:\n  val n: Int = \"one\"\n  val m: Int = true\n")
      val Left(errors) = ws.resolve(): @unchecked
      assert(errors.map(_.path).toList == List("m/src/s4s/Broken.scala", "m/src/s4s/Broken.scala"))
      assert(errors.map(_.line.intValue).toList == List(4, 5))
      assert(errors.head.msg.startsWith("Found:"))
      assert(!os.exists(ws.classDir / "s4s" / "Broken.class"))
      assert(ws.logger.messages.contains("2 compile error(s) mapped to their mutants for rollback"))
    }

    test("resolveTestRunners - nothing is compiled when the tmp dir holds no sources of the module") {
      val ws             = workspace()
      ws.write("NOTES.md", "not a source")
      val Right(runners) = ws.resolve(): @unchecked
      assert(runners.size == 1)
      assert(os.list(ws.classDir).isEmpty)
      assert(!ws.logger.messages.exists(_.startsWith("Compiling")))
    }

    test("resolveTestRunners - a compiler failure without diagnostics is one generic error on the tmp dir") {
      val ws           = workspace()
      ws.write("MutatedSuite.scala", instrumentedSuite)
      // A second -d after the runner's own: scalac rejects the missing directory before reading any source.
      val Left(errors) = ws.resolve(Seq("-d", (ws.root / "missing").toString)): @unchecked
      assert(errors.toList == List(CompilerErrMsg("Compilation failed", ws.tmpDir.toString, 0)))
    }

    test("resolveTestRunnerArtifact - the testrunner and its runtime, without its own Scala stdlib") {
      val names = Stryker4sMillRunner.resolveTestRunnerArtifact(scalaVersion).map(_.last)
      assert(names.contains("stryker4s-sbt-testrunner_3-0.21.0.jar"))
      assert(names.contains("stryker4s-testrunner-api_3-0.21.0.jar"))
      assert(names.exists(_.startsWith("scalapb-runtime_3-")))
      // The module's own classpath provides the stdlib; the testrunner's would shadow it, being built against an older Scala.
      assert(!names.exists(name => name.startsWith("scala3-library") || name.startsWith("scala-library")))
    }

    test("buildTestGroups - one group for the framework, one whole-suite task per test class") {
      val groups = Stryker4sMillRunner.buildTestGroups(testClasspath, utestFramework, Seq("a.One", "b.Two"))
      assert(groups == utestGroups("a.One", "b.Two"))
    }

    test("instrumenterOptions - the testrunner flavour: int literal switch cases and coverage calls") {
      given Config  = Config.default
      given Dialect = dialects.Scala3
      val runner    = workspace().runner()
      val options   = runner.instrumenterOptions
      assert(options.mutationContext == ActiveMutationContext.testRunner)
      assert(options.pattern(3).syntax == "3")
      val coverage  = options.coverageStatement.map(_(NonEmptyList.of(1, 2)).syntax)
      assert(coverage == Some("_root_.stryker4s.coverage.coverMutant(1, 2)"))
      assert(runner.extraConfigSources.isEmpty)
    }

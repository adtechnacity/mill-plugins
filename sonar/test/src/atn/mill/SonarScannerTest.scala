package atn.mill

import ch.qos.logback.classic.{Level, LoggerContext}
import ch.qos.logback.core.ConsoleAppender
import mill.*
import mill.api.{BuildCtx, Discover, ExecResult, PathRef, Result, SelectMode}
import mill.scalalib.*
import mill.testkit.{TestRootModule, UnitTester}
import org.slf4j.LoggerFactory
import org.sonarsource.scanner.lib.{ScannerEngineBootstrapResult, ScannerEngineFacade}
import utest.*

import scala.jdk.CollectionConverters.*

/**
 * Drives [[SonarScanner]] up to, but never into, the scanner engine: the property maps are built under `UnitTester`
 * from fixture modules, the environment is the tester's `env`, and the only path through the `sonar` command is the one
 * that fails before anything is bootstrapped.
 */
object SonarScannerTest extends TestSuite:

  private val token = Map("SONAR_TOKEN" -> "squ_secret")

  private def rootLogger = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext].getLogger("ROOT")

  /** The message `task` fails with under `eval`, or an assertion error when it does not fail. */
  private def failure(eval: UnitTester, task: Task[?]): String = eval(task) match
    case Left(ExecResult.Failure(msg, _)) => msg
    case other                            => throw new java.lang.AssertionError(s"Expected a failure but got $other")

  /** The properties `initProps` builds for `build.sonar` under `env`, through the fixture's `props` task. */
  private def props(build: SonarBuild, env: Map[String, String], withCoverage: Boolean = false): Map[String, String] =
    UnitTester(build, os.temp.dir(), env = env).scoped { eval =>
      val task = if withCoverage then build.sonar.propsWithCoverage else build.sonar.props
      eval(task).map(_.value).fold(f => throw new java.lang.AssertionError(s"initProps failed: $f"), identity)
    }

  val tests = Tests:

    test("defaults - env var names, coverage task path and base dir, and the sonar default task") {
      val build = new NoSourcesBuild()
      assert(build.sonar.sonarTokenEnvVar == "SONAR_TOKEN")
      assert(build.sonar.sonarLogLevelEnvVar == "SONAR_LOG_LEVEL")
      assert(build.sonar.coverageReportTaskPath == "scoverage.xmlReportAll")
      assert(build.sonar.sonarProjectBaseDir == BuildCtx.workspaceRoot)
      assert(SonarScanner.defaultTask() == "sonar")
      assert(SonarScanner.sonarHostUrl == "")
      assert(SonarScanner.sonarProjectKey == "")
      assert(SonarScanner.sonarProjectName == "")
    }

    test("moduleProps - a top-level module joins sonar.modules with its language and sources") {
      val srcs  = Seq(os.Path("/ws/app/src"), os.Path("/ws/app/src-gen"))
      val props = SonarScanner.moduleProps(Map.empty, "app", srcs)
      assert(
        props == Map(
          "sonar.modules"      -> "app",
          "app.sonar.language" -> "scala",
          "app.sonar.sources"  -> "/ws/app/src,/ws/app/src-gen"
        )
      )
      val both  = SonarScanner.moduleProps(props, "lib", Seq(os.Path("/ws/lib/src")))
      assert(both("sonar.modules") == "app,lib")
      assert(both("lib.sonar.sources") == "/ws/lib/src")
      assert(both("app.sonar.sources") == "/ws/app/src,/ws/app/src-gen")
    }

    test("moduleProps - a nested module's sources are its parent's tests, accumulated in order, however deep") {
      val first  = SonarScanner.moduleProps(Map.empty, "app.test", Seq(os.Path("/ws/app/test/src")))
      assert(first == Map("app.sonar.tests" -> "/ws/app/test/src"))
      val second = SonarScanner.moduleProps(first, "app.it", Seq(os.Path("/ws/app/it/src")))
      assert(second == Map("app.sonar.tests" -> "/ws/app/test/src,/ws/app/it/src"))
      val deep   = SonarScanner.moduleProps(second, "core.app.test", Seq(os.Path("/ws/core/app/test/src")))
      assert(deep == second + ("core.app.sonar.tests" -> "/ws/core/app/test/src"))
    }

    test("moduleProps - scoverage modules and Mill's own modules add nothing") {
      val props = Map("sonar.modules" -> "app")
      assert(SonarScanner.moduleProps(props, "app.scoverage", Seq(os.Path("/ws/app/src"))) == props)
      assert(SonarScanner.moduleProps(props, "mill.scalalib.ZincWorkerModule", Seq(os.Path("/ws/mill/src"))) == props)
    }

    test("logLevel - DEBUG, INFO and ERROR in any case; anything else is WARN") {
      assert(SonarScanner.logLevel("DEBUG") == Level.DEBUG)
      assert(SonarScanner.logLevel("info") == Level.INFO)
      assert(SonarScanner.logLevel("Error") == Level.ERROR)
      assert(SonarScanner.logLevel("WARN") == Level.WARN)
      assert(SonarScanner.logLevel("TRACE") == Level.WARN)
      assert(SonarScanner.logLevel("") == Level.WARN)
    }

    test("configureLogging - installs the bundled console appender once at the requested root level") {
      SonarScanner.configureLogging("debug")
      assert(rootLogger.getLevel == Level.DEBUG)
      SonarScanner.configureLogging("nonsense")
      assert(rootLogger.getLevel == Level.WARN)
      val appenders = rootLogger.iteratorForAppenders().asScala.toSeq
      assert(appenders.map(_.getName) == Seq("STDOUT"))
      assert(appenders.head.isInstanceOf[ConsoleAppender[?]])
    }

    test("scannerProps - server, project and run details; a branch other than main is measured against main") {
      val build  = new SonarBuild()
      val report = PathRef(os.Path("/ws/out/scoverage/xmlReportAll.dest"), quick = true)
      val props  = build.sonar.scannerProps("feature/x", "tok", Result.Success(report), "1.2.3", "INFO", os.Path("/wd"))
      assert(
        props == Map(
          "sonar.host.url"                   -> "https://sonar.example.invalid",
          "sonar.working.directory"          -> "/wd",
          "sonar.token"                      -> "tok",
          "sonar.projectKey"                 -> "example-key",
          "sonar.projectName"                -> "Example Project",
          "sonar.projectVersion"             -> "1.2.3",
          "sonar.projectBaseDir"             -> build.sonar.sonarProjectBaseDir.toString,
          "sonar.branch.name"                -> "feature/x",
          "sonar.scala.coverage.reportPaths" -> "/ws/out/scoverage/xmlReportAll.dest/scoverage.xml",
          "sonar.log.level"                  -> "INFO",
          "sonar.verbose"                    -> "false",
          "sonar.newCode.referenceBranch"    -> "main"
        )
      )
    }

    test("scannerProps - main has no reference branch, DEBUG is verbose, a missing report is an empty path") {
      val build = new SonarBuild()
      val props = build.sonar.scannerProps("main", "tok", Result.Failure("no report"), "1.2.3", "DEBUG", os.Path("/wd"))
      assert(!props.contains("sonar.newCode.referenceBranch"))
      assert(props("sonar.branch.name") == "main")
      assert(props("sonar.verbose") == "true")
      assert(props("sonar.log.level") == "DEBUG")
      assert(props("sonar.scala.coverage.reportPaths") == "")
    }

    test("initProps - the token and log level come from the build's environment, the branch from git") {
      val build = new SonarBuild()
      val got   = props(build, token ++ Map("SONAR_LOG_LEVEL" -> "DEBUG"))
      val head  = GitRepo.headBranch().get
      assert(got("sonar.token") == "squ_secret")
      assert(got("sonar.log.level") == "DEBUG")
      assert(got("sonar.verbose") == "true")
      assert(got("sonar.branch.name") == head)
      assert(got.get("sonar.newCode.referenceBranch") == Option.when(head != "main")("main"))
      assert(got("sonar.working.directory") == (build.moduleDir / "out" / "sonar" / "props.dest").toString)
      assert(got("sonar.projectVersion") == "1.2.3")
      assert(got("sonar.scala.coverage.reportPaths") == "")
    }

    test("initProps - defaults to WARN logging and points at the coverage report's scoverage.xml") {
      val build = new SonarBuild()
      val got   = props(build, token, withCoverage = true)
      assert(got("sonar.log.level") == "WARN")
      assert(got("sonar.verbose") == "false")
      val dest  = build.moduleDir / "out" / "sonar" / "propsWithCoverage.dest"
      assert(got("sonar.scala.coverage.reportPaths") == (dest / "coverage" / "scoverage.xml").toString)
    }

    test("initProps - an empty token variable fails before anything else is read") {
      val build = new SonarBuild()
      UnitTester(build, os.temp.dir(), env = Map("SONAR_TOKEN" -> "")).scoped { eval =>
        assert(failure(eval, build.sonar.props).contains("SONAR_TOKEN is not set"))
      }
    }

    test("moduleOf - the module path of a task; empty for a task on the root module") {
      val build = new RootSourcesBuild()
      UnitTester(build, os.temp.dir()).scoped { eval =>
        val tasks = eval.evaluator.resolveTasks(Seq("__.sources"), SelectMode.Multi).get
        assert(tasks.map(SonarScanner.moduleOf).toSet == Set("", "app", "app.test"))
      }
    }

    test("analysisProps - every module's sources from the build, nested ones as their parent's tests") {
      val build = new SonarBuild()
      UnitTester(build, os.temp.dir()).scoped { eval =>
        val props = build.sonar.analysisProps(eval.evaluator)
        val ws    = build.moduleDir
        assert(props("sonar.modules").split(',').toSet == Set("app", "lib"))
        assert(
          props - "sonar.modules" == Map(
            "app.sonar.language" -> "scala",
            "app.sonar.sources"  -> s"$ws/app/src",
            "app.sonar.tests"    -> s"$ws/app/test/src",
            "lib.sonar.language" -> "scala",
            "lib.sonar.sources"  -> s"$ws/lib/src,$ws/lib/src-extra"
          )
        )
      }
    }

    test("analysisProps - a build without sources is an internal error") {
      val build = new NoSourcesBuild()
      UnitTester(build, os.temp.dir()).scoped { eval =>
        val error = assertThrows[InternalError](build.sonar.analysisProps(eval.evaluator))
        assert(error.getMessage.contains("Could not resolve sources"))
      }
    }

    test("analyze - a bootstrapped engine analyses the properties; a rejected analysis or a failed bootstrap fails") {
      val props    = Map("sonar.modules" -> "app", "app.sonar.sources" -> "/ws/app/src")
      assert(SonarScanner.analyze(FakeEngine(successful = true, expected = props), props) == Result.Success(()))
      val rejected = SonarScanner.analyze(FakeEngine(successful = true, expected = Map.empty), props)
      assert(rejected.toEither == Left("Sonar analysis failed, read logs for details"))
      val failed   = SonarScanner.analyze(FakeEngine(successful = false, expected = props), props)
      assert(failed.toEither == Left("Sonar bootstrap failed, read logs for details"))
    }

    test("sonar - an exclusive command that fails on the missing token before the scanner is bootstrapped") {
      val build = new SonarBuild()
      UnitTester(build, os.temp.dir(), env = Map.empty).scoped { eval =>
        val command = build.sonar.sonar(eval.evaluator)
        assert(command.exclusive)
        assert(failure(eval, command).contains("SONAR_TOKEN is not set"))
        assert(os.exists(build.moduleDir / "out" / "app" / "coverageReport.dest"))
      }
    }

// --- Fixtures: the shape of the example workspace's build.mill ---

/**
 * A bootstrapped scanner engine that never contacts a server: the bootstrap succeeded when `successful`, and the
 * analysis succeeds only for exactly the `expected` properties.
 */
final case class FakeEngine(successful: Boolean, expected: Map[String, String])
    extends ScannerEngineBootstrapResult
    with ScannerEngineFacade:
  def isSuccessful(): Boolean                                 = successful
  def getEngineFacade(): ScannerEngineFacade                  =
    if successful then this else throw new IllegalStateException("no engine facade: the bootstrap failed")
  def analyze(props: java.util.Map[String, String]): Boolean  = props.asScala.toMap == expected
  def getBootstrapProperties(): java.util.Map[String, String] = java.util.Collections.emptyMap()
  def getServerVersion(): String                              = "fake"
  def isSonarQubeCloud(): Boolean                             = false
  def close(): Unit                                           = ()

/** The example workspace's `sonar` module configuration. */
trait ExampleSonar extends SonarScanner:
  override def defaultTask(): String = "sonar"
  def sonarHostUrl                   = "https://sonar.example.invalid"
  def sonarProjectKey                = "example-key"
  def sonarProjectName               = "Example Project"
  def projectVersion                 = Task("1.2.3")

// The modules are nested in classes rather than in objects: Scala 3 compiles objects nested in an object to static
// fields that Mill's reflective child discovery does not see, whereas a real build.mill is wrapped in a class by
// Mill's codegen and reflects fine. Each test instantiates its fixture, so every UnitTester gets a fresh module
// directory.
abstract class SonarRoot extends TestRootModule:
  object app extends ScalaModule:
    def scalaVersion   = "3.8.4"
    def coverageReport = Task(PathRef(Task.dest))
    object test      extends ScalaModule:
      def scalaVersion = "3.8.4"
    object scoverage extends ScalaModule:
      def scalaVersion = "3.8.4"

  object lib extends ScalaModule:
    def scalaVersion     = "3.8.4"
    override def sources = Task.Sources("src", "src-extra")

  object sonar extends ExampleSonar:
    override def coverageReportTaskPath = "app.coverageReport"

    /** [[initProps]] without a coverage report. */
    def props = Task(initProps(Result.Failure("no coverage report"), projectVersion()))

    /** [[initProps]] with a coverage report directory under this task's dest. */
    def propsWithCoverage = Task {
      val report = Task.dest / "coverage"
      os.makeDir.all(report)
      initProps(Result.Success(PathRef(report)), projectVersion())
    }

class SonarBuild extends SonarRoot:
  lazy val millDiscover: Discover = Discover[this.type]

/** A root module with its own `sources`, as a single-module build has. */
class RootSourcesBuild extends TestRootModule:
  def sources = Task.Sources("src")
  object app extends ScalaModule:
    def scalaVersion = "3.8.4"
    object test extends ScalaModule:
      def scalaVersion = "3.8.4"
  lazy val millDiscover: Discover = Discover[this.type]

class NoSourcesBuild extends TestRootModule:
  object sonar extends ExampleSonar
  lazy val millDiscover: Discover = Discover[this.type]

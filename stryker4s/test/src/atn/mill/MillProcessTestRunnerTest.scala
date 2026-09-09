package atn.mill

import cats.effect.IO
import mutationtesting.MutantStatus
import stryker4s.config.Config
import stryker4s.model.MutantId
import stryker4s.testrunner.api.*
import utest.*

import scala.concurrent.duration.*
import scala.util.Try

import MillProcessTestRunner.{newProcess, processSpec, ServerConfig}
import StrykerTestSupport.{*, given}

object MillProcessTestRunnerTest extends TestSuite:

  private val sep    = java.io.File.pathSeparator
  private val logDir = os.root / "out" / "m" / "strykerMutate.dest" / "testrunner-logs"
  private val socket = os.root / "s.sock"

  /** A server with nothing configured beyond where it runs and logs. */
  private def bareServer(workingDir: os.Path) = ServerConfig(Seq.empty, Seq.empty, Map.empty, workingDir, logDir)

  private val suite = "atn.mill.StrykerFixtureSuite"

  /** `testsToRun` of a mutant covered by two tests of the fixture suite. */
  private val covering =
    Seq(TestFile(suite, Seq(TestDefinition(TestDefinitionId(0), "first"), TestDefinition(TestDefinitionId(1), "second"))))

  /** Drive one [[MillProcessTestRunner]] over a scripted server. */
  private def withRunner[A](script: (Int, Request) => Option[Response])(body: MillProcessTestRunner => IO[A]): A =
    withFakeServer(script) { socketPath =>
      run(MillTestRunnerConnection.create(socketPath).use(conn => body(new MillProcessTestRunner(conn))))
    }

  /** A server that answers every request the same way. */
  private def always(response: Response): (Int, Request) => Option[Response] = (_, _) => Some(response)

  /** What the server reports for an initial run: `covered` maps mutant ids to the ids of the `files` covering them. */
  private def coverage(
    durationNanos: Long,
    covered: Map[Int, Seq[Int]],
    files: Map[Int, TestFile],
    successful: Boolean = true
  ): CoverageTestRunResult =
    val names = CoverageTestNameMap(
      files.map((id, file) => TestFileId(id) -> file),
      covered.map((mutant, tests) => MutantId(mutant) -> TestNames(tests.map(TestFileId(_))))
    )
    CoverageTestRunResult(successful, Some(names), durationNanos)

  val tests = Tests:

    test("processSpec - forwards the environment and java options to the forked server") {
      val server = ServerConfig(
        classpath = Seq(os.root / "cp" / "a.jar", os.root / "cp" / "b.jar"),
        javaOpts = Seq("-Xmx1G", "-Dfoo=bar"),
        env = Map("MILL_TEST_RESOURCE_DIR" -> "/ws/res", "PLUGIN_VERSION" -> "1.0.0"),
        workingDir = os.root / "work",
        logDir = logDir
      )
      val spec   = processSpec(server, socketPath = os.root / "tmp" / "s4s.sock")
      // The test module's forkEnv reaches the server process untouched (MILL_TEST_RESOURCE_DIR-style variables).
      assert(spec.env == Map("MILL_TEST_RESOURCE_DIR" -> "/ws/res", "PLUGIN_VERSION" -> "1.0.0"))
      // Java options sit between the classpath and the socket property; the main class comes last.
      assert(spec.args.containsSlice(Seq("-Xmx1G", "-Dfoo=bar")))
      assert(spec.args.last == "stryker4s.sbt.testrunner.SbtTestRunnerMain")
      assert(spec.args.contains(s"-D${TestProcessProperties.unixSocketPath}=${os.root / "tmp" / "s4s.sock"}"))
      val cp     = spec.args(spec.args.indexOf("-cp") + 1)
      assert(cp == Seq(os.root / "cp" / "a.jar", os.root / "cp" / "b.jar").mkString(sep))
      assert(spec.workingDir == os.root / "work")
    }

    test("processSpec - an empty environment adds nothing") {
      val spec = processSpec(bareServer(os.root), socket)
      assert(spec.env.isEmpty)
      assert(spec.args.head == "-cp")
    }

    test("processSpec - the server log lives in logDir, never in the stryker tmp dir the server works in") {
      val tmpDir = os.root / "out" / "m" / "strykerMutate.dest" / "target" / "stryker4s-1"
      val spec   = processSpec(bareServer(tmpDir), socket)
      // stryker4s deletes the tmp dir while the server may still hold its log open; on NFS that leaves a
      // `.nfsXXXX` entry behind and the delete fails with DirectoryNotEmptyException.
      assert(spec.logFile.startsWith(logDir))
      assert(!spec.logFile.startsWith(tmpDir))
      assert(spec.logFile.last.startsWith("testrunner-"))
      assert(spec.logFile.ext == "log")
      assert(spec.workingDir == tmpDir)
    }

    test("runMutant - sends the mutant id with the covering suites; all tests passing means it survived") {
      val expected = StartTestRun(MutantId(7), Seq(suite))
      val result   = withRunner { (_, request) =>
        Some(if request == expected then TestsSuccessful(2) else ErrorDuringTestRun(s"unexpected $request"))
      }(_.runMutant(mutant(7), covering))
      assert(result.id == "7")
      assert(result.status == MutantStatus.Survived)
      assert(result.testsCompleted == Some(2))
      assert(result.coveredBy == Some(Seq("0", "1")))
      assert(result.killedBy.isEmpty)
      assert(result.statusReason.isEmpty)
    }

    test("runMutant - failed tests kill the mutant, mapped back to their definition ids and messages") {
      val failed = Seq(
        FailedTestDefinition(suite, "second", Some("assertion failed")),
        FailedTestDefinition(suite, "first", None),
        FailedTestDefinition("other.Suite", "first", Some("elsewhere"))
      )
      val result = withRunner(always(TestsUnsuccessful(2, failed)))(_.runMutant(mutant(3), covering))
      assert(result.status == MutantStatus.Killed)
      assert(result.testsCompleted == Some(2))
      // killedBy holds the ids of the failed tests of suites that ran; the reason keeps every message reported.
      assert(result.killedBy == Some(Seq("1", "0")))
      assert(result.statusReason == Some("second: assertion failed\n\nfirst: elsewhere"))
      assert(result.coveredBy == Some(Seq("0", "1")))
    }

    test("runMutant - an error while running the tests kills the mutant with the error as reason") {
      val result = withRunner(always(ErrorDuringTestRun("boom")))(_.runMutant(mutant(1), covering))
      assert(result.status == MutantStatus.Killed)
      assert(result.statusReason == Some("boom"))
      assert(result.killedBy.isEmpty)
      assert(result.testsCompleted.isEmpty)
      assert(result.coveredBy == Some(Seq("0", "1")))
    }

    test("runMutant - any other answer is a runtime error") {
      val result = withRunner(always(SetupTestContextSuccessful()))(_.runMutant(mutant(1), covering))
      assert(result.status == MutantStatus.RuntimeError)
      assert(result.coveredBy == Some(Seq("0", "1")))
      assert(result.statusReason.isEmpty)
    }

    test("initialTestRun - runs the suite twice; mutants covered only the first time are static") {
      val file   = TestFile(suite, Seq(TestDefinition(TestDefinitionId(0), "first")))
      val first  = coverage(100, Map(1 -> Seq(0), 2 -> Seq(0)), Map(0 -> file))
      val second = coverage(300, Map(2 -> Seq(0)), Map(0 -> file))
      val result = withRunner((n, _) => Some(if n == 0 then first else second))(_.initialTestRun())
      assert(result.isSuccessful)
      assert(result.hasCoverage)
      // The timeout stryker4s derives from this is based on the average of the two runs.
      assert(result.reportedDuration == Some(200.nanos))
      assert(result.testNames == Seq(file))
      assert(result.staticMutants == Seq(MutantId(1)))
      assert(result.coveredMutants == Map(MutantId(2) -> Seq(file)))
    }

    test("initialTestRun - fails when either run fails") {
      val result = withRunner((n, _) => Some(coverage(10, Map.empty, Map.empty, successful = n == 0)))(_.initialTestRun())
      assert(!result.isSuccessful)
    }

    test("initialTestRun - an answer that is not a coverage report is a protocol error") {
      val error = Try(withRunner(always(TestsSuccessful(1)))(_.initialTestRun())).failed.get
      assert(error.isInstanceOf[MatchError])
    }

    test("sendMessage - a server that hangs up without answering is an error, not a hang") {
      val error = Try(withRunner((_, _) => None)(_.runMutant(mutant(1), covering))).failed.get
      assert(error.getMessage.contains("Failed to parse ResponseMessage"))
    }

    test("newProcess - forks a server that activates mutants in-process and runs the covering tests") {
      val ws                 = os.temp.dir()
      val server             = ServerConfig(
        classpath = testClasspath,
        javaOpts = Seq("-Ds4s.fixture=on", "-Xmx512m"),
        env = Map("S4S_FIXTURE" -> "on"),
        workingDir = ws,
        logDir = ws / "logs"
      )
      given Config           = Config.default
      val (initial, results) = run(newProcess(server, utestGroups(suite)).use(initialThenMutants(_, List(0, 1, 2))))
      assert(initial.isSuccessful)
      assert(initial.testNames.map(_.fullyQualifiedName) == Seq(suite))
      // The fixture only reports coverage when the java option reached the server.
      assert(initial.coveredMutants.keySet == Set(0, 1, 2, 3).map(MutantId(_)))
      assert(initial.staticMutants.isEmpty)
      // The fixture asserts against mutant 1; mutant 2 survives only because the environment reached the server.
      assert(results.map(_.status) == List(MutantStatus.Survived, MutantStatus.Killed, MutantStatus.Survived))
      val definitions        = initial.testNames.flatMap(_.definitions)
      val killer             = definitions.filter(_.name.contains("mutant 1 is killed")).map(_.id.toString)
      assert(killer.size == 1)
      assert(results(1).killedBy == Some(killer))
      assert(results(1).statusReason.exists(_.contains("mutant 1 is killed")))
      assert(results.forall(_.coveredBy == Some(definitionIds(initial.testNames))))
      val logs               = os.list(ws / "logs").filter(_.ext == "log")
      assert(logs.size == 1)
      val log                = os.read(logs.head)
      assert(log.contains("Set up testContext"))
      assert(log.contains("s4s-fixture: stderr reaches the log"))
    }

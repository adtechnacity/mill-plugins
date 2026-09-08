package atn.mill

import utest.*
import stryker4s.testrunner.api.TestProcessProperties
import MillProcessTestRunner.{processSpec, ServerConfig}

object MillProcessTestRunnerTest extends TestSuite:

  private val sep    = java.io.File.pathSeparator
  private val logDir = os.root / "out" / "m" / "strykerMutate.dest" / "testrunner-logs"
  private val socket = os.root / "s.sock"

  /** A server with nothing configured beyond where it runs and logs. */
  private def bareServer(workingDir: os.Path) = ServerConfig(Seq.empty, Seq.empty, Map.empty, workingDir, logDir)

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

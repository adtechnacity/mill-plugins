package atn.mill

import utest.*
import stryker4s.testrunner.api.TestProcessProperties

object MillProcessTestRunnerTest extends TestSuite:

  private val sep = java.io.File.pathSeparator

  val tests = Tests:

    test("processSpec - forwards the environment and java options to the forked server") {
      val spec = MillProcessTestRunner.processSpec(
        classpath = Seq(os.root / "cp" / "a.jar", os.root / "cp" / "b.jar"),
        javaOpts = Seq("-Xmx1G", "-Dfoo=bar"),
        env = Map("MILL_TEST_RESOURCE_DIR" -> "/ws/res", "PLUGIN_VERSION" -> "1.0.0"),
        socketPath = os.root / "tmp" / "s4s.sock",
        workingDir = os.root / "work"
      )
      // The test module's forkEnv reaches the server process untouched (MILL_TEST_RESOURCE_DIR-style variables).
      assert(spec.env == Map("MILL_TEST_RESOURCE_DIR" -> "/ws/res", "PLUGIN_VERSION" -> "1.0.0"))
      // Java options sit between the classpath and the socket property; the main class comes last.
      assert(spec.args.containsSlice(Seq("-Xmx1G", "-Dfoo=bar")))
      assert(spec.args.last == "stryker4s.sbt.testrunner.SbtTestRunnerMain")
      assert(spec.args.contains(s"-D${TestProcessProperties.unixSocketPath}=${os.root / "tmp" / "s4s.sock"}"))
      val cp   = spec.args(spec.args.indexOf("-cp") + 1)
      assert(cp == Seq(os.root / "cp" / "a.jar", os.root / "cp" / "b.jar").mkString(sep))
      assert(spec.workingDir == os.root / "work")
    }

    test("processSpec - an empty environment adds nothing") {
      val spec = MillProcessTestRunner.processSpec(Seq.empty, Seq.empty, Map.empty, os.root / "s.sock", os.root)
      assert(spec.env.isEmpty)
      assert(spec.args.head == "-cp")
    }

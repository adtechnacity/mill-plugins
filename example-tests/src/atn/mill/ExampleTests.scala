package atn.mill

import utest._
import mill.testkit.ExampleTester

/**
 * Runs a plugin's example workspace through Mill's `ExampleTester`, which executes the `Usage` block of its
 * `build.mill` against the locally published plugins. The workspace is the only directory under
 * `MILL_TEST_RESOURCE_DIR` (`<plugin>/example/resources/example-<plugin>`), so this one source is compiled into every
 * `example` module (see `MillPluginExampleTests.sources` in build.mill) instead of a copy per plugin.
 */
object ExampleTests extends TestSuite:
  val tests = Tests:
    test("example"):
      val resourceFolder = os.Path(sys.env("MILL_TEST_RESOURCE_DIR"))
      val workspaces     = os.list(resourceFolder).filter(os.isDir)
      assert(workspaces.size == 1)
      ExampleTester.run(
        daemonMode = false,
        workspaceSourcePath = workspaces.head,
        millExecutable = os.Path(sys.env("MILL_EXECUTABLE_PATH"))
      )

package atn.mill

import utest._
import mill.testkit.ExampleTester

object ExampleTests extends TestSuite:
  val tests = Tests:
    test("example"):
      val resourceFolder = os.Path(sys.env("MILL_TEST_RESOURCE_DIR"))
      ExampleTester.run(
        daemonMode = false,
        workspaceSourcePath = resourceFolder / "example-stryker4s",
        millExecutable = os.Path(sys.env("MILL_EXECUTABLE_PATH"))
      )

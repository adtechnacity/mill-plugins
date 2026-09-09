package atn.mill

import utest.*

/**
 * Runs inside the servers [[MillProcessTestRunnerTest]] forks, standing in for a mutated module: with the `s4s.fixture`
 * java option, which only those servers set, it makes the coverage calls and checks of the active mutant that
 * instrumented code makes. Under Mill's own test run, and under mutation testing of this module, the suite is inert:
 * registering coverage for fixed mutant ids there would corrupt the real report.
 */
object StrykerFixtureSuite extends TestSuite:

  private val forked = sys.props.get("s4s.fixture").contains("on")

  /** What an instrumented statement does: report the mutants it carries, then branch on the active one. */
  private def active(ids: Int*): Int =
    if forked then _root_.stryker4s.coverage.coverMutant(ids*)
    if forked then _root_.stryker4s.activeMutation else -1

  val tests = Tests:
    test("mutant 1 is killed, mutant 0 survives") {
      assert(active(0, 1) != 1)
    }
    test("mutant 2 survives only when the environment reached the server") {
      // The environment of the forked server is what is under test here, so this is the one place a test reads it.
      assert(active(2) != 2 || sys.env.get("S4S_FIXTURE").contains("on"))
    }
    test("what the suite writes to stderr ends up in the server log") {
      if forked then System.err.println("s4s-fixture: stderr reaches the log")
      assert(active(3) != 3)
    }

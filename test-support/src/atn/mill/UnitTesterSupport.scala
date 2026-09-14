package atn.mill

import mill.api.{ExecResult, Task}
import mill.testkit.{TestRootModule, UnitTester}

/**
 * The `UnitTester` scaffolding of the unit suites. This directory is compiled into every plugin's test module (see
 * `MillPluginTests.sources` in build.mill), the way `example-tests/src` is into every example module, so a helper lives
 * here once instead of once per module.
 *
 * The fixtures stay per module, in one shape: the modules under test are nested in an `abstract class` extending
 * `TestRootModule` rather than in an object (Scala 3 compiles objects nested in an object to static fields that Mill's
 * reflective child discovery does not see, whereas a real build.mill is wrapped in a class by Mill's codegen), and a
 * concrete `class` extending it holds `lazy val millDiscover = Discover[this.type]`, which the macro must expand in the
 * concrete class. Each test instantiates its build, so every tester gets a fresh module directory.
 */
object UnitTesterSupport:

  /** `body` on `build` under a tester over `workspace`, a fresh temporary directory unless given, closed afterwards. */
  def withBuild[B <: TestRootModule, T](build: B, workspace: os.Path = os.temp.dir())(body: (B, UnitTester) => T): T =
    UnitTester(build, workspace).scoped(eval => body(build, eval))

  /** The value `task` evaluates to under `eval`; an assertion error carrying the failure otherwise. */
  def value[T](eval: UnitTester, task: Task[T]): T = value(eval(task))

  /** The value of a successful evaluation; an assertion error carrying the failure otherwise. */
  def value[T](result: Either[ExecResult.Failing[T], UnitTester.Result[T]]): T =
    result.fold(failure => throw new java.lang.AssertionError(s"Expected success but got $failure"), _.value)

  /** The message a failed evaluation reports; an assertion error when it succeeded or threw instead. */
  def failureOf(result: Either[ExecResult.Failing[?], ?]): String = result match
    case Left(ExecResult.Failure(msg, _)) => msg
    case other                            => throw new java.lang.AssertionError(s"Expected a failure but got $other")

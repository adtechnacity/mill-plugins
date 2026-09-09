package atn.mill

import mill.api.daemon.Result

import org.scalacheck.{Prop, Test as PropTest}
import utest.*

import scala.jdk.CollectionConverters.*

object ResultExtensionsTest extends TestSuite:

  /** Never set anywhere, so the fallback chain ends in the default. */
  private val unset = "ATN_MILL_CORE_TEST_UNSET_VARIABLE"

  /** Some variable the test process does have, whichever it is: the fallback `orEnv` reads. */
  private val (present, presentValue) =
    System.getenv().asScala.find((_, v) => v.nonEmpty).getOrElse(throw new IllegalStateException("empty environment"))

  private def failing: Result[String] = Result.Failure("not available")

  private def throwing: Result[String] = throw new IllegalStateException("could not even try")

  private def holds(prop: Prop): Unit = assert(PropTest.check(prop)(identity).passed)

  val tests = Tests:

    test("a successful result is returned as is, whatever the environment or the default say") {
      holds(Prop.forAll((value: String, default: String) => Result.Success(value).orEnv(present, default) == value))
    }

    test("a failure falls back to the named environment variable") {
      assert(System.getenv(unset) == null)
      assert(failing.orEnv(present) == presentValue)
    }

    test("a failure without that variable yields the default, <n/a> unless given") {
      assert(failing.orEnv(unset) == "<n/a>")
      holds(Prop.forAll((default: String) => failing.orEnv(unset, default) == default))
    }

    test("an exception while producing the result counts as a failure") {
      assert(throwing.orEnv(present) == presentValue)
      assert(throwing.orEnv(unset) == "<n/a>")
    }

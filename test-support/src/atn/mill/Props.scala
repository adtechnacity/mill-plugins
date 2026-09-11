package atn.mill

import org.scalacheck.{Prop, Test}
import org.scalacheck.util.Pretty

/** ScalaCheck glue for every property suite: how a `Prop` becomes a utest assertion. */
object Props:

  /** Fails the enclosing utest test with ScalaCheck's own summary unless `prop` holds under the default parameters. */
  def holds(prop: Prop): Unit =
    val result = Test.check(Test.Parameters.default, prop)
    if !result.passed then throw new java.lang.AssertionError(Pretty.pretty(result))

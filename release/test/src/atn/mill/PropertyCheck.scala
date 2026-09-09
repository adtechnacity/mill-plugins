package atn.mill

import org.scalacheck.{Prop, Test}

/** ScalaCheck glue for the release property suites. */
object PropertyCheck:

  /** Fails the enclosing utest test unless `prop` holds under ScalaCheck's default parameters. */
  def holds(prop: Prop): Unit =
    val outcome = Test.check(Test.Parameters.default, prop)
    Predef.assert(outcome.passed, s"property failed: ${outcome.status}")

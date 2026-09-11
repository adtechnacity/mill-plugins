package atn.mill

import org.scalacheck.{Prop, Test}
import upickle.{default => json}
import utest.*

/** ScalaCheck glue shared by the devx property suites. */
object PropertyChecks:

  /** Runs `prop` with ScalaCheck's default parameters and fails the enclosing utest test unless it passed. */
  def checkProp(prop: Prop): Unit =
    val result = Test.check(prop)(identity)
    assert(result.passed)

  /** Serialises `value` to JSON and reads it back. */
  def roundTrip[A: json.ReadWriter](value: A): A = json.read[A](json.write(value))

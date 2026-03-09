package example

import utest._

object CalculatorTest extends TestSuite:
  val tests = Tests:
    test("add"):
      assert(Calculator.add(1, 2) == 3)
    test("isPositive"):
      assert(Calculator.isPositive(1))
      assert(!Calculator.isPositive(-1))
    test("clamp"):
      assert(Calculator.clamp(5, 0, 10) == 5)
      assert(Calculator.clamp(-1, 0, 10) == 0)
      assert(Calculator.clamp(11, 0, 10) == 10)

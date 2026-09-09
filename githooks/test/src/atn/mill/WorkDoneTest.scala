package atn.mill

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import upickle.default.{read, write}
import utest.*

object WorkDoneTest extends TestSuite:

  private val hooks: List[WorkDone] =
    List(WrotePreCommitHook, WrotePrePushHook, WrotePrepareCommitMsgHook, WroteCommitHook)

  private val genValue: Gen[Int] = Gen.choose(0, 15)

  val tests = Tests:

    test("each hook owns one bit, so a sum says exactly which hooks were written") {
      assert(NotAThing.value == 0)
      assert(hooks.map(_.value) == List(1, 2, 4, 8))
    }

    test("and - adds the work of both sides, starting from nothing") {
      assert(hooks.foldLeft[WorkDone](NotAThing)(_.and(_)).value == 15)
      assert(NotAThing.and(WrotePrePushHook).value == 2)
      assert(WrotePrePushHook.and(NotAThing).value == 2)
    }

    test("and - property: the value is the sum, whichever side comes first") {
      Props.holds(forAll(genValue, genValue) { (a, b) =>
        WorkDone(a).and(WorkDone(b)).value == a + b && WorkDone(b).and(WorkDone(a)).value == a + b
      })
    }

    test("json - written as the bare value and read back to the same value") {
      assert(write[WorkDone](WrotePrepareCommitMsgHook) == "4")
      assert(read[WorkDone]("15").value == 15)
      Props.holds(forAll(genValue)(v => read[WorkDone](write[WorkDone](WorkDone(v))).value == v))
    }

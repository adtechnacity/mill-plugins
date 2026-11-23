package atn.mill

import utest.*

object CodeSceneReviewTest extends TestSuite:

  private val sampleSingle =
    """{"path":"app/src/Foo.scala","score":9.5,"review":[{"category":"Primitive Obsession","description":"Too many primitives","indication":2}]}"""

  private val sampleArray =
    """[
      |  {"path":"app/src/Good.scala","score":10.0,"review":[]},
      |  {"path":"app/src/Warn.scala","score":8.5,"review":[{"category":"Complex Method","description":"cc=12","indication":1}]},
      |  {"path":"app/src/Unscorable.scala","score":null,"review":[]}
      |]""".stripMargin

  val tests = Tests:

    test("parse - handles both single-file JSON object and multi-file JSON array"):
      val single = CodeSceneReview.parse(sampleSingle)
      assert(single.size == 1)
      assert(single.head.path == "app/src/Foo.scala")
      assert(single.head.score == Some(9.5))
      assert(single.head.issues.map(_.category) == Seq("Primitive Obsession"))

      val multi = CodeSceneReview.parse(sampleArray)
      assert(multi.size == 3)
      assert(multi(0).score == Some(10.0))
      assert(multi(1).score == Some(8.5))
      assert(multi(2).score == None)

    test("evaluate - partitions files into passing, warnings, and errors; ignores unscorable files"):
      val files = CodeSceneReview.parse(sampleArray)
      val eval  = CodeSceneReview.evaluate(files, CodeSceneReview.Thresholds(warnScore = 9.0, errorScore = 8.0))
      assert(eval.errors.isEmpty)
      assert(eval.warnings.map(_.path) == Seq("app/src/Warn.scala"))
      assert(eval.unscored.map(_.path) == Seq("app/src/Unscorable.scala"))

    test("evaluate - score below errorScore is an error, not a warning"):
      val files = CodeSceneReview.parse(sampleArray)
      val eval  = CodeSceneReview.evaluate(files, CodeSceneReview.Thresholds(warnScore = 9.5, errorScore = 9.0))
      assert(eval.errors.map(_.path) == Seq("app/src/Warn.scala"))
      assert(eval.warnings.isEmpty)

    test("validateThresholds - rejects errorScore > warnScore or scores outside 0..10"):
      assert(CodeSceneReview.Thresholds(warnScore = 8.0, errorScore = 9.0).validate("prod").isLeft)
      assert(CodeSceneReview.Thresholds(warnScore = 11.0, errorScore = 8.0).validate("prod").isLeft)
      assert(CodeSceneReview.Thresholds(warnScore = 9.0, errorScore = -1.0).validate("prod").isLeft)
      assert(CodeSceneReview.Thresholds(warnScore = 9.0, errorScore = 8.0).validate("prod").isRight)

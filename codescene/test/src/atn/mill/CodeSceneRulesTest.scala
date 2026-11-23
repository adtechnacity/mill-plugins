package atn.mill

import utest.*

object CodeSceneRulesTest extends TestSuite:

  val tests = Tests:

    test("ruleSets - renders test rule sets before production rule sets (first match wins)"):
      val json = CodeSceneRules.render(
        prodPaths = Seq("app/src"),
        testPaths = Seq("app/test/src"),
        prodRules = Seq(CodeSceneRules.RuleWeight("Primitive Obsession", 0.5)),
        testRules = Seq(CodeSceneRules.RuleWeight("Large Method", 0.0)),
        prodThresholds = Seq(CodeSceneRules.RuleThreshold("function_lines_of_code_warning", 80)),
        testThresholds = Seq(CodeSceneRules.RuleThreshold("unit_test_consecutive_asserts_for_large_block", 12))
      )
      val sets = json("rule_sets").arr
      assert(sets.size == 2)
      assert(sets(0)("matching_content_path").str == "app/test/src/**")
      assert(sets(0)("rules").arr.head("name").str == "Large Method")
      assert(sets(0)("rules").arr.head("weight").num == 0.0)
      assert(sets(0)("thresholds").arr.head("name").str == "unit_test_consecutive_asserts_for_large_block")
      assert(sets(0)("thresholds").arr.head("value").num == 12)

      assert(sets(1)("matching_content_path").str == "app/src/**")
      assert(sets(1)("rules").arr.head("name").str == "Primitive Obsession")
      assert(sets(1)("rules").arr.head("weight").num == 0.5)
      assert(sets(1)("thresholds").arr.head("name").str == "function_lines_of_code_warning")
      assert(sets(1)("thresholds").arr.head("value").num == 80)

    test("ruleSets - omits empty rules/thresholds keys and normalizes trailing slashes"):
      val json = CodeSceneRules.render(
        prodPaths = Seq("app/src/"),
        testPaths = Seq("app/test/src/**"),
        prodRules = Seq.empty,
        testRules = Seq(CodeSceneRules.RuleWeight("Code Duplication", 0.0))
      )
      val sets = json("rule_sets").arr
      assert(sets.size == 1)
      assert(sets(0)("matching_content_path").str == "app/test/src/**")
      assert(!sets(0).obj.contains("thresholds"))

    test("merge - replaces matching_content_path entries while preserving unrelated rule sets"):
      val existing  = ujson.Obj(
        "rule_sets" -> ujson.Arr(
          ujson.Obj("matching_content_path" -> "other/**", "rules" -> ujson.Arr()),
          ujson.Obj(
            "matching_content_path"         -> "app/test/src/**",
            "rules"                         -> ujson.Arr(ujson.Obj("name" -> "Old", "weight" -> 0.5))
          )
        )
      )
      val generated = CodeSceneRules.render(
        prodPaths = Seq("app/src"),
        testPaths = Seq("app/test/src"),
        prodRules = Seq(CodeSceneRules.RuleWeight("Brain Method", 0.8)),
        testRules = Seq(CodeSceneRules.RuleWeight("Large Method", 0.0))
      )
      val merged    = CodeSceneRules.merge(Some(existing), generated)
      val paths     = merged("rule_sets").arr.map(_("matching_content_path").str).toList
      assert(paths == List("app/test/src/**", "app/src/**", "other/**"))
      val testRules = merged("rule_sets").arr.head("rules").arr
      assert(testRules.map(_("name").str).toList == List("Large Method"))

    test("validate - rejects out-of-range weights and non-positive thresholds"):
      val badWeight    =
        CodeSceneRules.validate(rules = Seq(CodeSceneRules.RuleWeight("Large Method", 1.5)), thresholds = Seq.empty)
      val badThreshold = CodeSceneRules.validate(
        rules = Seq.empty,
        thresholds = Seq(CodeSceneRules.RuleThreshold("function_lines_of_code_warning", 0))
      )
      assert(badWeight.isLeft)
      assert(badThreshold.isLeft)
      assert(CodeSceneRules.validate(Seq(CodeSceneRules.RuleWeight("Large Method", 0.0)), Seq.empty).isRight)

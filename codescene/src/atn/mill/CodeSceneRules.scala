package atn.mill

/**
 * Pure generation, validation and merging of CodeScene's `.codescene/code-health-rules.json` configuration.
 *
 * CodeScene evaluates `rule_sets` top-to-bottom and applies the first entry whose `matching_content_path` glob matches
 * a file. Test source globs are therefore emitted ahead of production source globs so test rules always take precedence
 * even when test directories live beneath a module's root.
 */
object CodeSceneRules:

  /** A rule weight override (`0.0` disables the rule, `1.0` keeps full weight). */
  final case class RuleWeight(name: String, weight: Double)

  /** A numeric threshold override for a CodeScene rule (positive integer). */
  final case class RuleThreshold(name: String, value: Int)

  /**
   * Default test-code rule weights: relax test-unfriendly rules while keeping test-specific assertion checks active.
   */
  val DefaultTestRules: Seq[RuleWeight] = Seq(RuleWeight("Code Duplication", 0.0), RuleWeight("Large Method", 0.5))

  /** Validates rule weights (`0.0 <= weight <= 1.0`) and thresholds (`value > 0`). */
  def validate(rules: Seq[RuleWeight], thresholds: Seq[RuleThreshold]): Either[String, Unit] =
    val badWeight    = rules.find(r => r.weight < 0.0 || r.weight > 1.0 || r.weight.isNaN)
    val badThreshold = thresholds.find(_.value <= 0)
    (badWeight, badThreshold) match
      case (Some(r), _) => Left(s"CodeScene rule '${r.name}' weight (${r.weight}) must be between 0.0 and 1.0")
      case (_, Some(t)) => Left(s"CodeScene threshold '${t.name}' value (${t.value}) must be positive")
      case (None, None) => Right(())

  /** Normalizes a workspace-relative directory or glob into a recursive CodeScene `matching_content_path` glob. */
  def toContentGlob(pathOrGlob: String): String =
    val trimmed = pathOrGlob.stripPrefix("./").stripSuffix("/")
    if trimmed.contains("*") then trimmed else s"$trimmed/**"

  /** Renders a single `rule_sets` entry when at least one rule or threshold is configured. */
  private def renderRuleSet(glob: String, rules: Seq[RuleWeight], thresholds: Seq[RuleThreshold]): Option[ujson.Obj] =
    if rules.isEmpty && thresholds.isEmpty then None
    else
      val obj = ujson.Obj("matching_content_path" -> glob)
      if rules.nonEmpty then
        obj("rules") = ujson.Arr.from(rules.map(r => ujson.Obj("name" -> r.name, "weight" -> r.weight)))
      if thresholds.nonEmpty then
        obj("thresholds") = ujson.Arr.from(thresholds.map(t => ujson.Obj("name" -> t.name, "value" -> t.value)))
      Some(obj)

  /**
   * Renders a `.codescene/code-health-rules.json` document with test rule sets ahead of production rule sets. Paths
   * with no configured rules or thresholds are omitted.
   */
  def render(
    prodPaths: Seq[String],
    testPaths: Seq[String],
    prodRules: Seq[RuleWeight] = Seq.empty,
    testRules: Seq[RuleWeight] = Seq.empty,
    prodThresholds: Seq[RuleThreshold] = Seq.empty,
    testThresholds: Seq[RuleThreshold] = Seq.empty
  ): ujson.Obj =
    val testSets = testPaths.map(toContentGlob).distinct.flatMap(renderRuleSet(_, testRules, testThresholds))
    val prodSets = prodPaths.map(toContentGlob).distinct.flatMap(renderRuleSet(_, prodRules, prodThresholds))
    ujson.Obj("rule_sets" -> ujson.Arr.from(testSets ++ prodSets))

  /**
   * Merges `generated` into an optional `existing` `.codescene/code-health-rules.json`: entries whose
   * `matching_content_path` is in `generated` are replaced by the newly generated ones (placed first), while all other
   * existing rule sets are preserved in order.
   */
  def merge(existing: Option[ujson.Value], generated: ujson.Obj): ujson.Obj =
    val generatedSets  = generated("rule_sets").arr.toSeq
    val generatedPaths = generatedSets.map(_("matching_content_path").str).toSet
    val keptExisting   = existing
      .flatMap(_.obj.get("rule_sets"))
      .map(_.arr.toSeq.filterNot(rs => generatedPaths.contains(rs("matching_content_path").str)))
      .getOrElse(Seq.empty)
    ujson.Obj("rule_sets" -> ujson.Arr.from(generatedSets ++ keptExisting))

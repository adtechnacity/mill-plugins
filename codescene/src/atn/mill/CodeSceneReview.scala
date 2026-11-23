package atn.mill

/**
 * Parsing and threshold evaluation for `cs review --output-format json`.
 *
 * `cs review` emits a JSON object when reviewing a single file and a JSON array when reviewing multiple files. A `null`
 * score means CodeScene found no scorable code in that file.
 */
object CodeSceneReview:

  /** One code health issue reported inside a file's `review` array. */
  final case class Issue(category: String, description: String, indication: Option[Int])

  /** Review result for a single source file (`score == None` when unscorable). */
  final case class FileReview(path: String, score: Option[Double], issues: Seq[Issue])

  /** Warn and error Code Health score thresholds (`0.0 <= errorScore <= warnScore <= 10.0`). */
  final case class Thresholds(warnScore: Double, errorScore: Double):

    /** Validates that both thresholds lie in `[0.0, 10.0]` and `errorScore <= warnScore`. */
    def validate(scope: String): Either[String, Unit] =
      if warnScore < 0.0 || warnScore > 10.0 || warnScore.isNaN then
        Left(s"CodeScene $scope warnScore ($warnScore) must be between 0.0 and 10.0")
      else if errorScore < 0.0 || errorScore > 10.0 || errorScore.isNaN then
        Left(s"CodeScene $scope errorScore ($errorScore) must be between 0.0 and 10.0")
      else if errorScore > warnScore then
        Left(s"CodeScene $scope errorScore ($errorScore) must be at most warnScore ($warnScore)")
      else Right(())

  /** Partitioned evaluation of reviewed files against a [[Thresholds]] pair. */
  final case class Evaluation(
    passed: Seq[FileReview],
    warnings: Seq[FileReview],
    errors: Seq[FileReview],
    unscored: Seq[FileReview]
  )

  /** Parses `cs review --output-format json` output (either a single JSON object or a JSON array). */
  def parse(jsonText: String): Seq[FileReview] =
    val trimmed = jsonText.trim
    if trimmed.isEmpty then Seq.empty
    else
      ujson.read(trimmed) match
        case arr: ujson.Arr => arr.arr.toSeq.map(parseEntry)
        case obj: ujson.Obj => Seq(parseEntry(obj))
        case other          =>
          throw new IllegalArgumentException(s"Unexpected cs review JSON root: ${other.getClass.getSimpleName}")

  private def parseEntry(value: ujson.Value): FileReview =
    val obj    = value.obj
    val path   = obj("path").str
    val score  = obj.get("score").flatMap {
      case ujson.Null => None
      case v          => Some(v.num)
    }
    val issues = obj.get("review").map(_.arr.toSeq.map(parseIssue)).getOrElse(Seq.empty)
    FileReview(path, score, issues)

  private def parseIssue(value: ujson.Value): Issue =
    val obj = value.obj
    Issue(
      category = obj.get("category").map(_.str).getOrElse("Unknown"),
      description = obj.get("description").map(_.str).getOrElse(""),
      indication = obj.get("indication").flatMap {
        case ujson.Null => None
        case v          => Some(v.num.toInt)
      }
    )

  /**
   * Evaluates `files` against `thresholds`: `< errorScore` is an error, `< warnScore` is a warning, `None` is unscored.
   */
  def evaluate(files: Seq[FileReview], thresholds: Thresholds): Evaluation =
    val (scored, unscored) = files.partition(_.score.isDefined)
    val (errors, nonError) = scored.partition(_.score.exists(_ < thresholds.errorScore))
    val (warnings, passed) = nonError.partition(_.score.exists(_ < thresholds.warnScore))
    Evaluation(passed = passed, warnings = warnings, errors = errors, unscored = unscored)

  /** Formats one file review into a concise single-line summary for logs and failure messages. */
  def formatFile(file: FileReview): String =
    val scoreStr = file.score.map(s => f"$s%.2f").getOrElse("n/a")
    val cats     = file.issues.map(_.category).distinct.mkString(", ")
    if cats.isEmpty then s"${file.path} (score=$scoreStr)"
    else s"${file.path} (score=$scoreStr: $cats)"

package atn.mill

import java.nio.file.FileSystems

import scala.util.matching.Regex

/** Why a signing condition demands a signed commit: the condition's name and a human-readable detail. */
final case class SigningReason(condition: String, detail: String)

object SigningReason {
  import upickle.default._
  implicit val rw: ReadWriter[SigningReason] = macroRW
}

/**
 * A pluggable trigger for commit-signing enforcement. Conditions are pure functions over a [[GitDiff.ChangeSet]]: an
 * empty result abstains, a non-empty result demands a GPG-signed commit for the change, one reason per finding. Custom
 * conditions supplied via `GitHooksModule.signingConditions` are evaluated identically to the built-ins.
 */
trait SigningCondition {
  def name: String
  def appliesTo(changes: GitDiff.ChangeSet): Vector[SigningReason]
}

/**
 * Fires when an added line matches any suppression-marker pattern. Patterns are matched word-bounded, on added lines
 * only — removing a marker, or committing near an existing one, never triggers.
 */
final case class ExceptionComments(markers: Seq[String]) extends SigningCondition {
  val name = "exception-comments"

  private val patterns = markers.map(p => SigningConditions.compileRegex(s"(?<![\\w])(?:$p)(?![\\w])", "marker", p))

  def appliesTo(changes: GitDiff.ChangeSet): Vector[SigningReason] =
    for {
      f <- changes
      l <- f.added
      p <- patterns
      if p.findFirstIn(l.text).isDefined
    } yield SigningReason(name, s"${f.path}:${l.number}: suppression marker added: '${l.text.trim}'")
}

/**
 * Fires on any change touching a protected path: entries containing `/` or `*` are java glob patterns matched against
 * the full repo-relative path, other entries match by file basename anywhere in the tree. Both the old and new side of
 * a change are checked, so renaming a file out of a protected location also triggers.
 */
final case class ProtectedPaths(globs: Seq[String]) extends SigningCondition {
  val name = "protected-paths"

  private val (basenames, globPatterns) = globs.partition(g => !g.exists(c => c == '/' || c == '*'))
  private val matchers                  = globPatterns.map { g =>
    try FileSystems.getDefault.getPathMatcher(s"glob:$g")
    catch {
      case e: RuntimeException =>
        throw new IllegalArgumentException(s"malformed protected path glob '$g': ${e.getMessage}")
    }
  }

  private def hits(path: String): Boolean =
    basenames.contains(path.split('/').last) || matchers.exists(_.matches(java.nio.file.Path.of(path)))

  def appliesTo(changes: GitDiff.ChangeSet): Vector[SigningReason] =
    changes.collect {
      case f if f.newPath.exists(hits) || f.legs.exists(l => hits(l.oldPath)) =>
        SigningReason(name, s"${f.path}: protected path changed")
    }
}

/**
 * Fail-closed guard: a change to a source path that cannot be text-diffed (content sniffs as binary) is treated as a
 * trigger, because line-based conditions cannot inspect it.
 */
final case class UndiffableChange(sourcePattern: String) extends SigningCondition {
  val name = "undiffable-change"

  private val source = SigningConditions.compileRegex(sourcePattern, "source path pattern", sourcePattern)

  def appliesTo(changes: GitDiff.ChangeSet): Vector[SigningReason] =
    changes.collect {
      case f if f.binary && (f.newPath ++ f.legs.map(_.oldPath)).exists(p => source.matches(p)) =>
        SigningReason(name, s"${f.path}: undiffable change to an inspectable path (fail-closed)")
    }
}

/**
 * Case-level test protection. Files whose old path lies under a test root have their test-case regions located in the
 * old revision (a region spans a case-pattern header line to the line before the next header); a removed line inside a
 * region marks that case as removed or modified. Pure insertions never fire — a known, documented boundary. Renaming a
 * case-bearing file out of the test root counts as removing its cases. For merge commits a case fires only when every
 * parent leg is affected (content a branch already removed was verified there).
 */
final case class TestProtection(testPathPattern: String, casePatterns: Seq[String]) extends SigningCondition {
  val name = "test-protection"

  private val testPath = SigningConditions.compileRegex(testPathPattern, "test path pattern", testPathPattern)
  private val cases    = casePatterns.map(p => SigningConditions.compileRegex(p, "test case pattern", p))

  private def isTest(path: String): Boolean = testPath.findFirstIn(path).isDefined

  /** (startLine, endLine, header) for each case region in `content`, 1-based inclusive. */
  private def regions(content: String): Vector[(Int, Int, String)] = {
    val lines   = content.split("\n", -1).toVector
    val headers = lines.zipWithIndex.collect {
      case (l, i) if cases.exists(_.findFirstIn(l).isDefined) => (i + 1, l.trim)
    }
    headers.zipWithIndex.map { case ((start, header), idx) =>
      (start, headers.lift(idx + 1).map(_._1 - 1).getOrElse(lines.size), header)
    }
  }

  private def affectedCases(leg: ParentLeg): Set[String] = {
    val rs = regions(leg.oldContent())
    leg.removed.flatMap(l => rs.collect { case (s, e, header) if l.number >= s && l.number <= e => header }).toSet
  }

  def appliesTo(changes: GitDiff.ChangeSet): Vector[SigningReason] =
    changes.flatMap { f =>
      val testLegs = f.legs.filter(l => isTest(l.oldPath))
      if (testLegs.isEmpty) Vector.empty
      else {
        val escaped  = f.newPath.exists(np => !isTest(np))
        val affected =
          if (escaped) regions(testLegs.head.oldContent()).map(_._3).toSet
          else testLegs.map(affectedCases).reduce(_ intersect _)
        affected.toVector.sorted.map(header =>
          SigningReason(name, s"${f.path}: test case removed or modified: '$header'")
        )
      }
    }
}

object SigningConditions {

  /** Suppression markers for scalafmt, scalafix, scalastyle, Sonar, CodeScene, and compiler-level suppression. */
  val DefaultMarkers: Seq[String] = Seq(
    """format:\s*off""",
    """scalafix:(?:off|ok)""",
    """scalastyle:off""",
    """NOSONAR""",
    """@codescene\(disable""",
    """@nowarn""",
    """@SuppressWarnings"""
  )

  /** Mill (`module/test/src`) and sbt/maven (`src/test`) test roots both contain a `test/` segment. */
  val DefaultTestPathPattern: String = "(^|/)test/"

  /** utest `test("…")` and ScalaCheck `property("…")` case headers. */
  val DefaultCasePatterns: Seq[String] = Seq("""\btest\s*\(\s*"""", """\bproperty\s*\(\s*"""")

  /** The trust store plus common quality-tool config files (basename entries match anywhere in the tree). */
  val DefaultProtectedGlobs: Seq[String] =
    Seq(".mill-signing/**", ".scalafix.conf", ".scalafmt.conf", "scalastyle-config.xml", "sonar-project.properties")

  /** Paths whose content the line-based conditions inspect; binary-sniffed changes to these fail closed. */
  val DefaultSourcePattern: String = """.*\.(scala|sc|sbt|java|kt|mill)$"""

  /** The built-in condition set with default configuration. */
  def defaults: Vector[SigningCondition] =
    defaults(DefaultMarkers, DefaultTestPathPattern, DefaultCasePatterns, DefaultProtectedGlobs, DefaultSourcePattern)

  def defaults(
    markers: Seq[String],
    testPathPattern: String,
    casePatterns: Seq[String],
    protectedGlobs: Seq[String],
    sourcePattern: String
  ): Vector[SigningCondition] = Vector(
    ExceptionComments(markers),
    TestProtection(testPathPattern, casePatterns),
    ProtectedPaths(protectedGlobs),
    UndiffableChange(sourcePattern)
  )

  /** Evaluate every condition over the change-set; non-empty means the change requires a signed commit. */
  def evaluate(conditions: Seq[SigningCondition], changes: GitDiff.ChangeSet): Vector[SigningReason] =
    conditions.toVector.flatMap(_.appliesTo(changes))

  private[mill] def compileRegex(pattern: String, kind: String, shown: String): Regex =
    try new Regex(pattern)
    catch {
      case e: RuntimeException =>
        throw new IllegalArgumentException(s"malformed $kind '$shown': ${e.getMessage}")
    }
}

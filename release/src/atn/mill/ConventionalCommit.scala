package atn.mill

/**
 * Parsed conventional commit: `breaking` is set by the `!` after the type or scope in the header, or by a
 * `BREAKING CHANGE:` (or `BREAKING-CHANGE:`) footer in the message body.
 */
case class ConventionalCommit(hash: String, typ: String, scope: Option[String], breaking: Boolean, description: String)

object ConventionalCommit:

  private val FirstLineRE = """^(\w+)(?:\(([\w\s,-]+)\))?(!)?:\s(.+)$""".r

  /** A breaking-change footer: the uppercase token opens the line and is followed by a colon and whitespace. */
  private val BreakingFooterRE = """^BREAKING[ -]CHANGE:\s.*""".r

  /**
   * Parse a conventional commit from its hash and full message: the header line gives the type, scope, `!` and
   * description; any line below it that starts with `BREAKING CHANGE:` or `BREAKING-CHANGE:` also marks the commit
   * breaking, as the conventional commits specification requires of that footer.
   */
  def parse(hash: String, message: String): Option[ConventionalCommit] =
    message.linesIterator.toList match
      case FirstLineRE(typ, scope, bang, desc) :: body =>
        val breaking = bang != null || body.exists(BreakingFooterRE.matches)
        Some(ConventionalCommit(hash, typ, Option(scope), breaking, desc.trim))
      case _                                           => None

package atn.mill

/** Pure-function changelog generation from conventional commits. */
object ChangelogGenerator:

  /** Conventional commit type to changelog section heading. */
  val DefaultTypeMapping: Map[String, String] = Map(
    "feat"     -> "Added",
    "fix"      -> "Fixed",
    "refactor" -> "Changed",
    "style"    -> "Changed",
    "perf"     -> "Changed",
    "chore"    -> "Other",
    "ci"       -> "Other",
    "docs"     -> "Other",
    "test"     -> "Other",
    "build"    -> "Other"
  )

  /** Section display order. */
  private val SectionOrder =
    Seq("Breaking Changes", "Added", "Fixed", "Changed", "Other")

  /** Generate a changelog section for a single version. */
  def generate(
    version: String,
    date: String,
    commits: List[ConventionalCommit],
    typeMapping: Map[String, String] = DefaultTypeMapping
  ): String =
    val breaking = commits.filter(_.breaking)
    val grouped  = commits.groupBy(c => typeMapping.getOrElse(c.typ, "Other"))

    val sections = SectionOrder.flatMap: heading =>
      val entries =
        if heading == "Breaking Changes" then breaking
        else grouped.getOrElse(heading, Nil)
      if entries.isEmpty then None
      else Some(renderSection(heading, entries))

    s"## [$version] - $date\n\n${sections.mkString("\n\n")}\n"

  /** Format a single changelog entry. */
  private def renderEntry(c: ConventionalCommit): String =
    c.scope match
      case Some(s) => s"- **$s**: ${c.description}"
      case None    => s"- ${c.description}"

  /** Render a section heading with entries. */
  private def renderSection(heading: String, entries: Seq[ConventionalCommit]): String =
    s"### $heading\n\n${entries.map(renderEntry).mkString("\n")}"

  /** Create or update a CHANGELOG.md file. */
  def updateFile(existingContent: Option[String], newSection: String): String =
    existingContent match
      case None          =>
        s"# Changelog\n\n$newSection"
      case Some(content) =>
        val header = "# Changelog"
        if content.startsWith(header) then
          val rest = content.stripPrefix(header).stripLeading()
          s"$header\n\n$newSection\n$rest"
        else s"$header\n\n$newSection\n$content"

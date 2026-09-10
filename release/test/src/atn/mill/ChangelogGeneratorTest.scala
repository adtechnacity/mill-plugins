package atn.mill

import utest._

object ChangelogGeneratorTest extends TestSuite:

  val sampleCommits = List(
    ConventionalCommit("aaa", "feat", Some("core"), false, "add git helpers"),
    ConventionalCommit("bbb", "fix", None, false, "correct null handling"),
    ConventionalCommit("ccc", "refactor", Some("api"), true, "redesign module interface"),
    ConventionalCommit("ddd", "chore", None, false, "bump dependencies"),
    ConventionalCommit("eee", "feat", Some("docs"), false, "add scaladoc generation"),
    ConventionalCommit("fff", "docs", None, false, "update README")
  )

  private val wipOnly = List(ConventionalCommit("aaa", "wip", None, false, "half done"))

  val tests = Tests:

    test("generate - renders the populated sections in order, breaking entries listed under their type too"):
      val md       = ChangelogGenerator.generate("0.2.0", "2026-03-09", sampleCommits)
      val expected =
        """## [0.2.0] - 2026-03-09
          |
          |### Breaking Changes
          |
          |- **api**: redesign module interface
          |
          |### Added
          |
          |- **core**: add git helpers
          |- **docs**: add scaladoc generation
          |
          |### Fixed
          |
          |- correct null handling
          |
          |### Changed
          |
          |- **api**: redesign module interface
          |
          |### Other
          |
          |- bump dependencies
          |- update README
          |""".stripMargin
      assert(md == expected)

    test("generate - empty commit list produces minimal section"):
      val md = ChangelogGenerator.generate("0.1.0", "2026-01-01", Nil)
      assert(md == "## [0.1.0] - 2026-01-01\n\n\n")

    test("generate - unmapped types land under Other"):
      val md = ChangelogGenerator.generate("0.1.0", "2026-01-01", wipOnly)
      assert(md == "## [0.1.0] - 2026-01-01\n\n### Other\n\n- half done\n")

    test("generate - typeMapping moves a type to another section"):
      val remapped = ChangelogGenerator.DefaultTypeMapping + ("wip" -> "Changed")
      val md       = ChangelogGenerator.generate("0.1.0", "2026-01-01", wipOnly, remapped)
      assert(md == "## [0.1.0] - 2026-01-01\n\n### Changed\n\n- half done\n")

    test("updateFile - creates new file when no existing content"):
      val section = "## [0.1.0] - 2026-01-01\n\n### Added\n\n- initial release\n"
      val result  = ChangelogGenerator.updateFile(None, section)
      assert(result == s"# Changelog\n\n$section")

    test("updateFile - the new section sits under the single header, one blank line above the older sections"):
      val existing = "# Changelog\n\n\n\n## [0.1.0] - 2026-01-01\n\n### Added\n\n- initial\n"
      val section  = "## [0.2.0] - 2026-03-09\n\n### Fixed\n\n- a bug\n"
      val expected =
        "# Changelog\n\n## [0.2.0] - 2026-03-09\n\n### Fixed\n\n- a bug\n\n## [0.1.0] - 2026-01-01\n\n### Added\n\n- initial\n"
      assert(ChangelogGenerator.updateFile(Some(existing), section) == expected)

    test("updateFile - content without the header gains one and is kept verbatim below the new section"):
      val existing = "  Notes kept exactly as they were\n"
      val section  = "## [0.2.0] - 2026-03-09\n"
      assert(ChangelogGenerator.updateFile(Some(existing), section) == s"# Changelog\n\n$section\n$existing")

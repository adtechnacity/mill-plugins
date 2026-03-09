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

  val tests = Tests:

    test("generate - produces version header"):
      val md = ChangelogGenerator.generate("0.2.0", "2026-03-09", sampleCommits)
      assert(md.contains("## [0.2.0] - 2026-03-09"))

    test("generate - groups feats under Added"):
      val md = ChangelogGenerator.generate("0.2.0", "2026-03-09", sampleCommits)
      assert(md.contains("### Added"))
      assert(md.contains("**core**: add git helpers"))
      assert(md.contains("**docs**: add scaladoc generation"))

    test("generate - groups fixes under Fixed"):
      val md = ChangelogGenerator.generate("0.2.0", "2026-03-09", sampleCommits)
      assert(md.contains("### Fixed"))
      assert(md.contains("correct null handling"))

    test("generate - breaking changes get own section"):
      val md = ChangelogGenerator.generate("0.2.0", "2026-03-09", sampleCommits)
      assert(md.contains("### Breaking Changes"))
      assert(md.contains("redesign module interface"))

    test("generate - empty sections are omitted"):
      val featsOnly = List(ConventionalCommit("aaa", "feat", None, false, "new feature"))
      val md        = ChangelogGenerator.generate("0.1.0", "2026-01-01", featsOnly)
      assert(!md.contains("### Fixed"))
      assert(!md.contains("### Breaking Changes"))
      assert(!md.contains("### Changed"))

    test("generate - empty commit list produces minimal section"):
      val md = ChangelogGenerator.generate("0.1.0", "2026-01-01", Nil)
      assert(md.contains("## [0.1.0] - 2026-01-01"))

    test("generate - scope is bold when present, omitted when absent"):
      val md = ChangelogGenerator.generate("0.1.0", "2026-01-01", sampleCommits)
      assert(md.contains("**core**: add git helpers"))
      assert(md.contains("- correct null handling"))

    test("updateFile - creates new file when no existing content"):
      val section = "## [0.1.0] - 2026-01-01\n\n### Added\n\n- initial release\n"
      val result  = ChangelogGenerator.updateFile(None, section)
      assert(result.startsWith("# Changelog"))
      assert(result.contains("## [0.1.0] - 2026-01-01"))

    test("updateFile - prepends to existing content"):
      val existing = "# Changelog\n\n## [0.1.0] - 2026-01-01\n\n### Added\n\n- initial\n"
      val section  = "## [0.2.0] - 2026-03-09\n\n### Fixed\n\n- a bug\n"
      val result   = ChangelogGenerator.updateFile(Some(existing), section)
      val idx1     = result.indexOf("0.2.0")
      val idx2     = result.indexOf("0.1.0")
      assert(idx1 < idx2)

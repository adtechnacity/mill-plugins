package atn.mill

import utest._

object ConventionalCommitTest extends TestSuite:

  val tests = Tests:

    test("parse - feat with scope"):
      val cc = ConventionalCommit.parse("abc123", "feat(core): add git helpers")
      assert(cc.isDefined)
      val c  = cc.get
      assert(c.hash == "abc123")
      assert(c.typ == "feat")
      assert(c.scope == Some("core"))
      assert(!c.breaking)
      assert(c.description == "add git helpers")

    test("parse - fix without scope"):
      val cc = ConventionalCommit.parse("def456", "fix: correct null handling")
      assert(cc.isDefined)
      val c  = cc.get
      assert(c.typ == "fix")
      assert(c.scope.isEmpty)
      assert(c.description == "correct null handling")

    test("parse - breaking change with bang"):
      val cc = ConventionalCommit.parse("789abc", "refactor(api)!: redesign module interface")
      assert(cc.isDefined)
      assert(cc.get.breaking)

    test("parse - chore type"):
      val cc = ConventionalCommit.parse("aaa111", "chore: bump dependencies")
      assert(cc.isDefined)
      assert(cc.get.typ == "chore")

    test("parse - non-conventional message returns None"):
      assert(ConventionalCommit.parse("bad000", "random commit message").isEmpty)
      assert(ConventionalCommit.parse("bad001", "no type here").isEmpty)
      assert(ConventionalCommit.parse("bad002", "").isEmpty)

    test("parse - multi-word scope"):
      val cc = ConventionalCommit.parse("bbb222", "feat(mill-build, core): multi scope")
      assert(cc.isDefined)
      assert(cc.get.scope == Some("mill-build, core"))

    test("parse - docs type"):
      val cc = ConventionalCommit.parse("ccc333", "docs: update README")
      assert(cc.isDefined)
      assert(cc.get.typ == "docs")

    test("parse - ci type"):
      val cc = ConventionalCommit.parse("ddd444", "ci: fix workflow")
      assert(cc.isDefined)
      assert(cc.get.typ == "ci")

package atn.mill

import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.propBoolean
import utest._

import PropertyCheck.holds

object ConventionalCommitTest extends TestSuite:
  import Prop.forAll

  /** Word characters: the only ones a commit type may contain. */
  private val genWordChar: Gen[Char] = Gen.frequency((9, Gen.alphaNumChar), (1, Gen.const('_')))

  private val genType: Gen[String] = Gen.nonEmptyListOf(genWordChar).map(_.mkString)

  /** Scopes: word characters, spaces, commas and hyphens, never empty. */
  private val genScope: Gen[String] =
    Gen.nonEmptyListOf(Gen.frequency((6, genWordChar), (1, Gen.oneOf(' ', ',', '-')))).map(_.mkString)

  /** Single-line descriptions, never empty, using the punctuation the header itself relies on. */
  private val genDescription: Gen[String] =
    Gen
      .nonEmptyListOf(Gen.frequency((6, genWordChar), (2, Gen.const(' ')), (1, Gen.oneOf('!', ':', '(', ')', '.'))))
      .map(_.mkString)

  /** The parts of a well-formed header: type, optional scope, bang, description. */
  private val genHeader: Gen[(String, Option[String], Boolean, String)] =
    for
      typ         <- genType
      scope       <- Gen.option(genScope)
      bang        <- Gen.oneOf(true, false)
      description <- genDescription
    yield (typ, scope, bang, description)

  /** Spells the header line the way conventional commits do. */
  private def header(typ: String, scope: Option[String], bang: Boolean, description: String): String =
    s"$typ${scope.fold("")(s => s"($s)")}${if bang then "!" else ""}: $description"

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

    test("parse - the colon must be followed by whitespace and a description"):
      assert(ConventionalCommit.parse("h", "feat:no space").isEmpty)
      assert(ConventionalCommit.parse("h", "feat: ").isEmpty)
      assert(ConventionalCommit.parse("h", "feat:").isEmpty)

    test("parse - type and scope must be non-empty and made of word characters"):
      assert(ConventionalCommit.parse("h", ": missing type").isEmpty)
      assert(ConventionalCommit.parse("h", "fe-at: dashed type").isEmpty)
      assert(ConventionalCommit.parse("h", "feat(): empty scope").isEmpty)
      assert(ConventionalCommit.parse("h", "feat(a.b): dotted scope").isEmpty)

    test("parse - the bang follows the scope and marks the commit breaking"):
      val cc = ConventionalCommit.parse("h", "feat(core)!: drop the old api")
      assert(cc == Some(ConventionalCommit("h", "feat", Some("core"), true, "drop the old api")))
      assert(ConventionalCommit.parse("h", "feat!(core): bang before scope").isEmpty)

    test("parse - surrounding whitespace in the description is trimmed"):
      assert(ConventionalCommit.parse("h", "fix:   padded   ").map(_.description) == Some("padded"))

    test("property - a well-formed header parses back into its parts, description trimmed, hash kept"):
      holds(forAll(genHeader, Gen.alphaNumStr) { case ((typ, scope, bang, description), hash) =>
        ConventionalCommit.parse(hash, header(typ, scope, bang, description))
          == Some(ConventionalCommit(hash, typ, scope, bang, description.trim))
      })

    test("property - only the first line of a message is parsed"):
      holds(forAll(genHeader, Gen.listOf(Gen.asciiPrintableStr)) { case ((typ, scope, bang, description), body) =>
        val line = header(typ, scope, bang, description)
        ConventionalCommit.parse("h", (line :: body).mkString("\n")) == ConventionalCommit.parse("h", line)
      })

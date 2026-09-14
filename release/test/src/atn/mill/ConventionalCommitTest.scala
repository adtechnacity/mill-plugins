package atn.mill

import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.propBoolean
import utest._

import Props.holds

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

  /**
   * First lines that are not `type(scope)!: description`: no header at all, a colon not followed by whitespace and a
   * description, an empty type or scope, a type or scope with a non-word character, and the bang before the scope.
   */
  private val malformed = List(
    "",
    "random commit message",
    "no type here",
    "feat:no space",
    "feat: ",
    "feat:",
    ": missing type",
    "fe-at: dashed type",
    "feat(): empty scope",
    "feat(a.b): dotted scope",
    "feat!(core): bang before scope"
  )

  val tests = Tests:

    test("parse - a malformed first line returns None"):
      for message <- malformed do assert(ConventionalCommit.parse("h", message).isEmpty)

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

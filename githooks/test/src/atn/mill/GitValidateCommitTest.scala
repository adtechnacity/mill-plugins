package atn.mill

import mill.api.Result
import mill.api.daemon.Logger.DummyLogger
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import utest.*

import GitValidateCommit.{DefaultTypes, MaxHeaderLength, MinHeaderLength}

object GitValidateCommitTest extends TestSuite:

  private val modules = Set("core", "app")

  private lazy val repo = GitFixtures.emptyRepo().getRepository

  private def validator(types: List[String] = DefaultTypes) = new GitValidateCommit(repo, types, modules, DummyLogger)

  private def accepted(msg: String): Boolean = validator().validate(msg) == Result.Success(())

  /** The failure message `validate` produces for `msg`; a success is a test failure. */
  private def failure(msg: String, types: List[String] = DefaultTypes): String = validator(types).validate(msg) match
    case f: Result.Failure => f.error
    case ok                => throw new java.lang.AssertionError(s"expected '$msg' to be rejected, got $ok")

  /** A description of exactly `len` characters. */
  private def description(len: Int): String = "x" * len

  val tests = Tests:

    test("validate - accepts a header with a known type and a module scope") {
      assert(accepted("feat(core): add the greeting"))
    }

    test("validate - the scope is optional") {
      assert(accepted("fix: repair the greeting"))
    }

    test("validate - a breaking-change marker is accepted") {
      assert(accepted("feat(app)!: drop the greeting"))
    }

    test("validate - comment lines are skipped, the header is the first real line") {
      assert(accepted("# Please enter the commit message\n#\nfeat(core): add the greeting\n\nSome body."))
    }

    test("validate - an unknown type is reported with the header") {
      val err = failure("wat(core): add the greeting")
      assert(err.startsWith("Illegal conventional commit.\n* wat is not a valid type\n"))
      assert(err.endsWith("\nwat(core): add the greeting"))
    }

    test("validate - a scope that is not a module is reported") {
      assert(failure("feat(nope): add the greeting").contains("* nope is not a valid module"))
    }

    test("validate - every failed check is listed, one per line") {
      val err = failure("wat(nope): short")
      assert(err.contains("* wat is not a valid type,\n* nope is not a valid module,\n* Message too short\n"))
    }

    test("validate - a header that is not a conventional commit cannot be decoded") {
      assert(failure("Fixed stuff") == "Could not decode conventional commit from:\nFixed stuff")
      assert(failure("") == "Could not decode conventional commit from:\n")
      assert(failure("# only comments\n# in here").startsWith("Could not decode conventional commit from:"))
    }

    test("validate - the description must be longer than MinHeaderLength and shorter than MaxHeaderLength") {
      assert(failure(s"feat: ${description(MinHeaderLength)}").contains("* Message too short"))
      assert(accepted(s"feat: ${description(MinHeaderLength + 1)}"))
      assert(accepted(s"feat: ${description(MaxHeaderLength - 1)}"))
      assert(failure(s"feat: ${description(MaxHeaderLength)}").contains("* Message too long"))
    }

    test("validate - property: exactly the descriptions strictly inside the bounds pass") {
      Props.holds(forAll(Gen.choose(1, 2 * MaxHeaderLength)) { len =>
        accepted(s"feat: ${description(len)}") == (len > MinHeaderLength && len < MaxHeaderLength)
      })
    }

    test("types - the three-argument constructor takes the default types; a custom list replaces them") {
      val defaults = new GitValidateCommit(repo, modules, DummyLogger)
      assert(defaults.validate("refactor(core): tidy the greeting") == Result.Success(()))
      assert(failure("refactor(core): tidy the greeting", List("feat", "fix")).contains("* refactor is not a valid type"))
    }

    test("checks - each check pairs its verdict with the message a failure shows") {
      val v = validator()
      assert(v.checkType("feat") == (true, "feat is not a valid type"))
      assert(v.checkType("wat") == (false, "wat is not a valid type"))
      assert(v.checkModule("core") == (true, "core is not a valid module"))
      assert(v.checkModule("nope") == (false, "nope is not a valid module"))
      // The regex hands over null when the header has no scope; that is not a module error.
      assert(v.checkModule(null) == (true, "null is not a valid module"))
      assert(v.checkBreaking("!") == (true, "breaking commits not checked"))
      assert(v.checkMessage(MinHeaderLength) == List(false -> "Message too short", true -> "Message too long"))
      assert(v.checkMessage(MaxHeaderLength) == List(true -> "Message too short", false -> "Message too long"))
    }

    test("DefaultTypes - the conventional set, sorted") {
      assert(DefaultTypes == List("chore", "ci", "docs", "feat", "fix", "refactor", "style"))
      assert(MinHeaderLength == 12)
      assert(MaxHeaderLength == 70)
    }

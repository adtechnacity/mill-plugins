package atn.mill

import utest._
import upickle.{default => json}
import org.scalacheck.{Arbitrary, Gen, Prop, Test}
import org.scalacheck.Prop.{forAll, propBoolean}

object CodeSceneTest extends TestSuite:

  // -- Generators --

  val genDevSettingsEntry: Gen[CodeScene.DevSettingsEntry] =
    for
      id   <- Gen.posNum[Int]
      name <- Gen.alphaNumStr.suchThat(_.nonEmpty)
      ref  <- Gen.alphaNumStr.suchThat(_.nonEmpty)
    yield CodeScene.DevSettingsEntry(id, name, ref)

  val genDeveloper: Gen[CodeScene.Developer] =
    for
      id                <- Gen.posNum[Int]
      name              <- Gen.alphaNumStr.suchThat(_.nonEmpty)
      teamName          <- Gen.alphaNumStr.suchThat(_.nonEmpty)
      email             <- Gen.alphaNumStr.map(s => s"$s@example.com")
      emails            <- Gen.listOf(Gen.alphaNumStr.map(s => s"$s@example.com"))
      formerContributor <- Gen.oneOf(true, false)
      ref               <- Gen.alphaNumStr.suchThat(_.nonEmpty)
    yield CodeScene.Developer(id, name, teamName, email, emails, formerContributor, ref)

  given Arbitrary[CodeScene.DevSettingsEntry] = Arbitrary(genDevSettingsEntry)
  given Arbitrary[CodeScene.Developer]        = Arbitrary(genDeveloper)

  private def checkProp(prop: Prop): Unit =
    val result = Test.check(prop)(identity)
    assert(result.passed)

  val tests = Tests:

    test("api - base URL is CodeScene v2 endpoint"):
      assert(CodeScene.api == "https://api.codescene.io/v2")

    test("DevSettingsEntry"):

      test("round-trip serialization identity"):
        checkProp(forAll { (entry: CodeScene.DevSettingsEntry) =>
          val roundTripped = json.read[CodeScene.DevSettingsEntry](json.write(entry))
          (roundTripped == entry).label(s"round-trip failed for $entry, got $roundTripped")
        })

      test("list round-trip serialization identity"):
        checkProp(forAll(Gen.listOf(genDevSettingsEntry)) { entries =>
          val roundTripped = json.read[List[CodeScene.DevSettingsEntry]](json.write(entries))
          (roundTripped == entries).label("list round-trip failed")
        })

      test("implements Entry trait with correct field projection"):
        checkProp(forAll { (entry: CodeScene.DevSettingsEntry) =>
          val asEntry: CodeScene.Entry = entry
          (asEntry.id == entry.id).label("id mismatch")
          && (asEntry.name == entry.name).label("name mismatch")
          && (asEntry.ref == entry.ref).label("ref mismatch")
        })

      test("JSON contains all fields"):
        checkProp(forAll { (entry: CodeScene.DevSettingsEntry) =>
          val parsed = ujson.read(json.write(entry))
          parsed.obj.contains("id").label("missing id")
          && parsed.obj.contains("name").label("missing name")
          && parsed.obj.contains("ref").label("missing ref")
          && (parsed("id").num.toInt == entry.id).label("id value mismatch")
        })

    test("Developer"):

      test("round-trip serialization identity"):
        checkProp(forAll { (dev: CodeScene.Developer) =>
          val roundTripped = json.read[CodeScene.Developer](json.write(dev))
          (roundTripped == dev).label(s"round-trip failed for $dev")
        })

      test("implements Entry trait with correct field projection"):
        checkProp(forAll { (dev: CodeScene.Developer) =>
          val asEntry: CodeScene.Entry = dev
          (asEntry.id == dev.id).label("id mismatch")
          && (asEntry.name == dev.name).label("name mismatch")
          && (asEntry.ref == dev.ref).label("ref mismatch")
        })

      test("emails list length is preserved through serialization"):
        checkProp(forAll { (dev: CodeScene.Developer) =>
          val roundTripped = json.read[CodeScene.Developer](json.write(dev))
          (roundTripped.emails.length == dev.emails.length).label("emails length changed")
        })

      test("former_contributor flag is preserved through serialization"):
        checkProp(forAll { (dev: CodeScene.Developer) =>
          val roundTripped = json.read[CodeScene.Developer](json.write(dev))
          (roundTripped.former_contributor == dev.former_contributor).label("former_contributor changed")
        })

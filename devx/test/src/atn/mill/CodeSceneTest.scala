package atn.mill

import utest._
import upickle.{default => json}
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Prop.{forAll, propBoolean}
import PropertyChecks.{checkProp, roundTrip}

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

  val tests = Tests:

    test("api - base URL is CodeScene v2 endpoint"):
      assert(CodeScene.api == "https://api.codescene.io/v2")

    test("DevSettingsEntry"):

      test("round-trip serialization identity"):
        checkProp(forAll { (entry: CodeScene.DevSettingsEntry) =>
          val roundTripped = roundTrip(entry)
          (roundTripped == entry).label(s"round-trip failed for $entry, got $roundTripped")
        })

      test("list round-trip serialization identity"):
        checkProp(forAll(Gen.listOf(genDevSettingsEntry)) { entries =>
          (roundTrip(entries) == entries).label("list round-trip failed")
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
        checkProp(forAll((dev: CodeScene.Developer) => (roundTrip(dev) == dev).label(s"round-trip failed for $dev")))

      test("implements Entry trait with correct field projection"):
        checkProp(forAll { (dev: CodeScene.Developer) =>
          val asEntry: CodeScene.Entry = dev
          (asEntry.id == dev.id).label("id mismatch")
          && (asEntry.name == dev.name).label("name mismatch")
          && (asEntry.ref == dev.ref).label("ref mismatch")
        })

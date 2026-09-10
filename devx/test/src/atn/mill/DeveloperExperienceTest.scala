package atn.mill

import utest._
import org.scalacheck.{Arbitrary, Gen, Prop}
import org.scalacheck.Prop.{forAll, propBoolean}
import PropertyChecks.checkProp

object DeveloperExperienceTest extends TestSuite:
  import DeveloperExperience.*

  /** `teamsToCreate` over generated team names and existing teams, handed to `check` as (all, existing, result). */
  private def forAllTeamsToCreate(check: (List[String], Set[String], List[String]) => Prop): Unit =
    checkProp(forAll(genTeamNames, genTeamNames) { (allNames, existingNames) =>
      val existing = existingNames.toSet
      check(allNames, existing, teamsToCreate(mkTeamsJson(allNames), existing))
    })

  // -- Generators --

  /** Strings that exercise slugify: mixed case, spaces, special chars, unicode. */
  val genSlugInput: Gen[String] = Gen.frequency(
    (5, Gen.alphaNumStr),
    (2, Gen.asciiStr),
    (1, Gen.const("")),
    (1, Gen.const("--leading--")),
    (1, Gen.const("  spaced  ")),
    (1, Gen.listOf(Gen.oneOf(' ', '-', '!', '@', '#', 'A', 'z', '3')).map(_.mkString))
  )

  /** Team name generator for building ujson structures. */
  val genTeamName: Gen[String] = Gen.alphaNumStr.suchThat(_.nonEmpty)

  /** Generate a teams JSON payload from a list of team names. */
  def mkTeamsJson(names: List[String]): ujson.Value =
    ujson.Obj("teams" -> ujson.Arr(names.map(n => ujson.Obj("name" -> n))*))

  /** Generate a developers JSON payload. */
  def mkDevsJson(devs: List[(Int, String, Boolean)]): ujson.Value =
    ujson.Obj("developers" -> ujson.Arr(devs.map { case (id, teamName, former) =>
      ujson.Obj("id" -> id, "team_name" -> teamName, "former_contributor" -> former)
    }*))

  val genTeamNames: Gen[List[String]] = Gen.listOf(genTeamName)

  val genDevEntry: Gen[(Int, String, Boolean)] =
    for
      id       <- Gen.posNum[Int]
      teamName <- genTeamName
      former   <- Arbitrary.arbitrary[Boolean]
    yield (id, teamName, former)

  val tests = Tests:

    test("slugify"):

      test("idempotency - applying slugify twice yields the same result"):
        checkProp(forAll(genSlugInput) { input =>
          val once  = slugify(input)
          val twice = slugify(once)
          (twice == once).label(s"slugify not idempotent: '$input' -> '$once' -> '$twice'")
        })

      test("output contains only lowercase alphanumeric and dashes"):
        checkProp(forAll(genSlugInput) { input =>
          val result = slugify(input)
          result
            .forall(c => c.isLetter && c.isLower || c.isDigit || c == '-')
            .label(s"invalid chars in slugify('$input') = '$result'")
        })

      test("no leading or trailing dashes"):
        checkProp(forAll(genSlugInput) { input =>
          val result = slugify(input)
          (result.isEmpty || (!result.startsWith("-") && !result.endsWith("-")))
            .label(s"leading/trailing dash in slugify('$input') = '$result'")
        })

      test("no consecutive dashes"):
        checkProp(forAll(genSlugInput) { input =>
          val result = slugify(input)
          (!result.contains("--"))
            .label(s"consecutive dashes in slugify('$input') = '$result'")
        })

      test("output length never exceeds input length"):
        checkProp(forAll(genSlugInput) { input =>
          val result = slugify(input)
          (result.length <= input.length)
            .label(s"slugify('$input') = '$result' is longer than input")
        })

    test("writeJson"):

      test("round-trip: creates the parent directories and reads back what it wrote"):
        checkProp(forAll(Gen.alphaNumStr.suchThat(_.nonEmpty), Gen.chooseNum(-100, 100)) { (key, value) =>
          val tmp      = os.temp.dir()
          val path     = tmp / "sub" / "dir" / "data.json"
          val data     = ujson.Obj(key -> value)
          writeJson(path, data)
          val readBack = ujson.read(os.read(path))
          (readBack(key).num.toInt == value)
            .label(s"round-trip failed for key='$key', value=$value")
        })

      test("overwrites existing file"):
        val tmp    = os.temp.dir()
        val path   = tmp / "overwrite.json"
        writeJson(path, ujson.Obj("v" -> 1))
        writeJson(path, ujson.Obj("v" -> 2))
        val parsed = ujson.read(os.read(path))
        assert(parsed("v").num.toInt == 2)

      test("writes with indent 2 formatting"):
        val tmp     = os.temp.dir()
        val path    = tmp / "formatted.json"
        writeJson(path, ujson.Obj("a" -> 1))
        val content = os.read(path)
        assert(content.contains("  "))

    test("tryFetch - recovers from exception without throwing"):
      checkProp(forAll(Gen.alphaNumStr) { msg =>
        tryFetch("test", "proj")(throw new RuntimeException(msg))
        Prop.passed
      })

    test("teamNames - extracts exactly the name field from each team object"):
      checkProp(forAll(genTeamNames) { names =>
        val json   = mkTeamsJson(names)
        val result = teamNames(json)
        (result == names).label(s"expected $names, got $result")
      })

    test("teamsToCreate"):

      test("result is a subset of teamNames"):
        forAllTeamsToCreate { (allNames, _, result) =>
          result
            .forall(allNames.contains)
            .label(s"result $result contains names not in $allNames")
        }

      test("result has no intersection with existing teams"):
        forAllTeamsToCreate { (_, existing, created) =>
          val result = created.toSet
          result
            .intersect(existing)
            .isEmpty
            .label(s"result $result intersects with existing $existing")
        }

      test("union of result and existing teams covers all team names"):
        forAllTeamsToCreate { (allNames, existing, created) =>
          val result = created.toSet
          val allSet = allNames.toSet
          allSet
            .subsetOf(result.union(existing))
            .label(s"$allSet not covered by result $result ++ existing $existing")
        }

      test("with empty existing set, returns all team names"):
        checkProp(forAll(genTeamNames) { names =>
          val json   = mkTeamsJson(names)
          val result = teamsToCreate(json, Set.empty)
          (result == names).label(s"expected all names $names, got $result")
        })

    test("resolveDevAssignments"):

      test("result only contains devs whose team is in teamsByName"):
        checkProp(forAll(Gen.listOf(genDevEntry), genTeamNames) { (devEntries, extraTeamNames) =>
          val allTeamNames = (devEntries.map(_._2) ++ extraTeamNames).distinct
          val teamsByName  = allTeamNames.zipWithIndex.toMap
          val devsJson     = mkDevsJson(devEntries)
          val result       = resolveDevAssignments(devsJson, teamsByName)
          (result.length == devEntries.length)
            .label(s"expected ${devEntries.length} assignments, got ${result.length}")
        })

      test("skips devs with unknown teams"):
        checkProp(forAll(Gen.listOf(genDevEntry)) { devEntries =>
          val devsJson = mkDevsJson(devEntries)
          val result   = resolveDevAssignments(devsJson, Map.empty)
          result.isEmpty.label(s"expected empty, got $result")
        })

      test("preserves formerContributor flag"):
        checkProp(forAll(Gen.listOf(genDevEntry)) { devEntries =>
          val teamsByName = devEntries.map(_._2).distinct.zipWithIndex.toMap
          val devsJson    = mkDevsJson(devEntries)
          val result      = resolveDevAssignments(devsJson, teamsByName)
          val expected    = devEntries.map(_._3)
          val actual      = result.map(_.formerContributor)
          (actual == expected).label(s"formerContributor mismatch: expected $expected, got $actual")
        })

      test("result length never exceeds input dev count"):
        checkProp(forAll(Gen.listOf(genDevEntry), Gen.mapOf(Gen.zip(genTeamName, Gen.posNum[Int]))) {
          (devEntries, teamsByName) =>
            val devsJson = mkDevsJson(devEntries)
            val result   = resolveDevAssignments(devsJson, teamsByName)
            (result.length <= devEntries.length)
              .label(s"result has ${result.length} entries but input had ${devEntries.length}")
        })

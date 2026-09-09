package atn.mill

import org.scalacheck.Gen
import org.scalacheck.Prop.{forAll, propBoolean}
import utest._

import PropertyCheck.holds

object SemVerTest extends TestSuite:

  /** Versions whose components stay far from Int overflow, so that every bump is exact. */
  private val genVersion: Gen[SemVer] =
    for
      major <- Gen.chooseNum(0, 100000)
      minor <- Gen.chooseNum(0, 100000)
      patch <- Gen.chooseNum(0, 100000)
    yield SemVer(major, minor, patch)

  val tests = Tests:

    test("parse - standard version"):
      val v = SemVer.parse("1.2.3")
      assert(v == Some(SemVer(1, 2, 3)))

    test("parse - with v prefix"):
      val v = SemVer.parse("v1.2.3")
      assert(v == Some(SemVer(1, 2, 3)))

    test("parse - with SNAPSHOT suffix"):
      val v = SemVer.parse("1.2.3-SNAPSHOT")
      assert(v == Some(SemVer(1, 2, 3)))

    test("parse - with v prefix and SNAPSHOT suffix"):
      val v = SemVer.parse("v0.3.0-SNAPSHOT")
      assert(v == Some(SemVer(0, 3, 0)))

    test("parse - with whitespace"):
      val v = SemVer.parse("  1.0.0\n")
      assert(v == Some(SemVer(1, 0, 0)))

    test("parse - invalid"):
      assert(SemVer.parse("").isEmpty)
      assert(SemVer.parse("1.2").isEmpty)
      assert(SemVer.parse("abc").isEmpty)
      assert(SemVer.parse("1.2.x").isEmpty)

    test("parse - a fourth component or a non-numeric one is rejected"):
      assert(SemVer.parse("1.2.3.4").isEmpty)
      assert(SemVer.parse("1.x.3").isEmpty)
      assert(SemVer.parse("v.2.3").isEmpty)

    test("bumpPatch"):
      assert(SemVer(1, 2, 3).bumpPatch == SemVer(1, 2, 4))

    test("bumpMinor"):
      assert(SemVer(1, 2, 3).bumpMinor == SemVer(1, 3, 0))

    test("bumpMajor"):
      assert(SemVer(1, 2, 3).bumpMajor == SemVer(2, 0, 0))

    test("release string"):
      assert(SemVer(1, 2, 3).release == "1.2.3")

    test("snapshot string"):
      assert(SemVer(1, 2, 3).snapshot == "1.2.3-SNAPSHOT")

    test("property - release and snapshot strings round-trip through parse, with or without the v prefix"):
      holds(forAll(genVersion) { v =>
        List(v.release, v.snapshot, s"v${v.release}", s"v${v.snapshot}", s"  ${v.release}\n")
          .forall(SemVer.parse(_) == Some(v))
      })

    test("property - bumps increment one component and reset the lower ones"):
      holds(forAll(genVersion) { v =>
        v.bumpPatch == SemVer(v.major, v.minor, v.patch + 1)
        && v.bumpMinor == SemVer(v.major, v.minor + 1, 0)
        && v.bumpMajor == SemVer(v.major + 1, 0, 0)
      })

    test("property - release renders the three components and snapshot appends -SNAPSHOT"):
      holds(forAll(genVersion) { v =>
        v.release == s"${v.major}.${v.minor}.${v.patch}" && v.snapshot == s"${v.release}-SNAPSHOT"
      })

    test("property - any number of components other than three fails to parse"):
      holds(forAll(Gen.listOf(Gen.chooseNum(0, 1000)).suchThat(_.size != 3)) { parts =>
        SemVer.parse(parts.mkString(".")).isEmpty
      })

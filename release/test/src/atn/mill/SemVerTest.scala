package atn.mill

import org.scalacheck.Gen
import org.scalacheck.Prop.{forAll, propBoolean}
import utest._

import Props.holds

object SemVerTest extends TestSuite:

  /** Versions whose components stay far from Int overflow, so that every bump is exact. */
  private val genVersion: Gen[SemVer] =
    for
      major <- Gen.chooseNum(0, 100000)
      minor <- Gen.chooseNum(0, 100000)
      patch <- Gen.chooseNum(0, 100000)
    yield SemVer(major, minor, patch)

  val tests = Tests:

    test("parse - a non-numeric component is rejected in any position (v.2.3 strips to an empty first one)"):
      for s <- List("1.2.x", "1.x.3", "v.2.3") do assert(SemVer.parse(s).isEmpty)

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

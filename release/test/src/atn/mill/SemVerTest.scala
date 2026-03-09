package atn.mill

import utest._

object SemVerTest extends TestSuite:

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

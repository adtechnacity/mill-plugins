package atn.mill

import utest._

object SigningConditionsTest extends TestSuite {

  /** A single-leg changed file: `old` supplies (content, removed lines); `added` supplies new-side lines. */
  def file(
    path: String,
    added: Seq[(Int, String)] = Nil,
    old: Option[(String, Seq[(Int, String)])] = None,
    newPath: Option[String] = null,
    binary: Boolean = false
  ): ChangedFile =
    ChangedFile(
      newPath = Option(newPath).getOrElse(Some(path)),
      legs = old.toVector.map { case (content, removed) =>
        ParentLeg(path, removed.toVector.map(DiffLine.apply.tupled), () => content)
      },
      binary = binary,
      added = added.toVector.map(DiffLine.apply.tupled)
    )

  val exception      = ExceptionComments(SigningConditions.DefaultMarkers)
  val protectedPaths = ProtectedPaths(SigningConditions.DefaultProtectedGlobs)
  val undiffable     = UndiffableChange(SigningConditions.DefaultSourcePattern)
  val testProtection = TestProtection(SigningConditions.DefaultTestPathPattern, SigningConditions.DefaultCasePatterns)

  val suite =
    """package x
      |object Helpers { def shared = 1 }
      |object T extends TestSuite {
      |  val tests = Tests {
      |    test("first") {
      |      assert(1 == 1)
      |      assert(2 == 2)
      |    }
      |    test("second") {
      |      assert(3 == 3)
      |    }
      |    property("third holds") {
      |      forAll(x => x == x)
      |    }
      |  }
      |}
      |""".stripMargin

  val testPath = "githooks/test/src/atn/mill/SomeTest.scala"

  val tests = Tests {

    test("exception comments") {
      test("every default marker fires on an added line") {
        val markers = Seq(
          "// format: off",
          "// scalafix:off rule",
          "// scalafix:ok",
          "// scalastyle:off",
          "val x = 1 // NOSONAR",
          "// @codescene(disable:\"Complex Method\")",
          "@nowarn(\"cat=deprecation\")",
          "@SuppressWarnings(Array(\"unchecked\"))"
        )
        markers.foreach { line =>
          val reasons = exception.appliesTo(Vector(file("A.scala", added = Seq(1 -> line))))
          assert(reasons.nonEmpty)
        }
      }

      test("multiple markers give multiple reasons") {
        val cs = Vector(file("A.scala", added = Seq(1 -> "// NOSONAR", 5 -> "// scalastyle:off")))
        assert(exception.appliesTo(cs).size == 2)
      }

      test("removed and unchanged marker lines never fire") {
        val cs = Vector(file("A.scala", old = Some("// NOSONAR\n" -> Seq(1 -> "// NOSONAR"))))
        assert(exception.appliesTo(cs).isEmpty)
      }

      test("marker inside a longer word does not fire") {
        val cs = Vector(file("A.scala", added = Seq(1 -> "val MYNOSONARISH = 1", 2 -> "XNOSONAR")))
        assert(exception.appliesTo(cs).isEmpty)
      }

      test("marker in a brand-new file fires") {
        val cs = Vector(file("New.scala", added = Seq(3 -> "// NOSONAR")))
        assert(exception.appliesTo(cs).nonEmpty)
      }

      test("malformed pattern fails loudly at construction") {
        val e = scala.util.Try(ExceptionComments(Seq("(unclosed"))).failed.get
        assert(e.isInstanceOf[IllegalArgumentException])
        assert(e.getMessage.contains("(unclosed"))
      }
    }

    test("protected paths") {
      test("trusted-keys dir change fires") {
        val cs = Vector(file(".mill-signing/trusted-keys/dev.asc", added = Seq(1 -> "-----BEGIN PGP PUBLIC KEY-----")))
        assert(protectedPaths.appliesTo(cs).nonEmpty)
      }

      test("tool config edit fires anywhere in the tree") {
        val cs = Vector(file("sub/module/.scalafix.conf", added = Seq(1 -> "rules = []")))
        assert(protectedPaths.appliesTo(cs).nonEmpty)
      }

      test("unrelated file abstains") {
        val cs = Vector(file("core/src/atn/mill/GitRepo.scala", added = Seq(1 -> "x")))
        assert(protectedPaths.appliesTo(cs).isEmpty)
      }

      test("rename out of a protected glob fires") {
        val cs =
          Vector(file(".mill-signing/trusted-keys/dev.asc", old = Some("key" -> Nil), newPath = Some("attic/dev.asc")))
        assert(protectedPaths.appliesTo(cs).nonEmpty)
      }

      test("malformed glob fails loudly at construction") {
        val e = scala.util.Try(ProtectedPaths(Seq("ok/**", "bad/["))).failed.get
        assert(e.isInstanceOf[IllegalArgumentException])
        assert(e.getMessage.contains("bad/["))
      }
    }

    test("undiffable change to an inspectable path fires fail-closed") {
      val scala = Vector(file("core/src/Thing.scala", binary = true))
      val image = Vector(file("docs/logo.png", binary = true))
      assert(undiffable.appliesTo(scala).nonEmpty)
      assert(undiffable.appliesTo(image).isEmpty)
    }

    test("custom conditions evaluate alongside built-ins") {
      val custom  = new SigningCondition {
        def name                                                    = "no-todos"
        def appliesTo(cs: GitDiff.ChangeSet): Vector[SigningReason] =
          cs.flatMap(f => f.added.filter(_.text.contains("TODO")).map(l => SigningReason(name, s"${f.path}:${l.number}")))
      }
      val cs      = Vector(file("A.scala", added = Seq(1 -> "// TODO refactor", 2 -> "// NOSONAR")))
      val reasons = SigningConditions.evaluate(Vector(exception, custom), cs)
      assert(reasons.map(_.condition).toSet == Set("exception-comments", "no-todos"))
    }

    test("test protection") {
      test("deleting a whole test case fires naming the case") {
        val removed = Seq(9 -> "    test(\"second\") {", 10 -> "      assert(3 == 3)", 11 -> "    }")
        val cs      = Vector(file(testPath, old = Some(suite -> removed)))
        val reasons = testProtection.appliesTo(cs)
        assert(reasons.size == 1)
        assert(reasons.head.detail.contains("second"))
      }

      test("editing an assertion inside an existing case fires") {
        val cs = Vector(
          file(testPath, added = Seq(6 -> "      assert(1 == 2)"), old = Some(suite -> Seq(6 -> "      assert(1 == 1)")))
        )
        assert(testProtection.appliesTo(cs).nonEmpty)
      }

      test("renaming a case header fires") {
        val cs = Vector(
          file(
            testPath,
            added = Seq(5 -> "    test(\"first renamed\") {"),
            old = Some(suite -> Seq(5 -> "    test(\"first\") {"))
          )
        )
        assert(testProtection.appliesTo(cs).nonEmpty)
      }

      test("appending a new case never fires") {
        val cs =
          Vector(file(testPath, added = Seq(15 -> "    test(\"fourth\") { assert(true) }"), old = Some(suite -> Nil)))
        assert(testProtection.appliesTo(cs).isEmpty)
      }

      test("a brand-new test file never fires") {
        val cs = Vector(file(testPath, added = Seq(1 -> "test(\"all new\") {}")))
        assert(testProtection.appliesTo(cs).isEmpty)
      }

      test("pure insertion inside an existing case body never fires") {
        val cs = Vector(file(testPath, added = Seq(7 -> "      assert(9 == 9)"), old = Some(suite -> Nil)))
        assert(testProtection.appliesTo(cs).isEmpty)
      }

      test("helper edit outside any case region never fires") {
        val cs = Vector(
          file(
            testPath,
            added = Seq(2 -> "object Helpers { def shared = 2 }"),
            old = Some(suite -> Seq(2 -> "object Helpers { def shared = 1 }"))
          )
        )
        assert(testProtection.appliesTo(cs).isEmpty)
      }

      test("non-test files abstain entirely") {
        val cs = Vector(file("core/src/Main.scala", old = Some(suite -> Seq(5 -> "    test(\"first\") {"))))
        assert(testProtection.appliesTo(cs).isEmpty)
      }

      test("rename out of the test root fires for its cases") {
        val cs      = Vector(file(testPath, old = Some(suite -> Nil), newPath = Some("attic/SomeTest.scala")))
        val reasons = testProtection.appliesTo(cs)
        assert(reasons.size == 3)
      }

      test("rename within the test root with no removals never fires") {
        val cs =
          Vector(file(testPath, old = Some(suite -> Nil), newPath = Some("githooks/test/src/atn/mill/Renamed.scala")))
        assert(testProtection.appliesTo(cs).isEmpty)
      }

      test("whole-file deletion fires once per case") {
        val allLines = suite.linesIterator.toVector.zipWithIndex.map { case (t, i) => i + 1 -> t }
        val cs       = Vector(file(testPath, old = Some(suite -> allLines), newPath = None))
        assert(testProtection.appliesTo(cs).size == 3)
      }

      test("merge: case deleted relative to all parents fires") {
        val removed = Seq(9 -> "    test(\"second\") {", 10 -> "      assert(3 == 3)")
        val legs    = Vector(
          ParentLeg(testPath, removed.toVector.map(DiffLine.apply.tupled), () => suite),
          ParentLeg(testPath, removed.toVector.map(DiffLine.apply.tupled), () => suite)
        )
        val cs      = Vector(ChangedFile(Some(testPath), legs, binary = false, added = Vector.empty))
        assert(testProtection.appliesTo(cs).nonEmpty)
      }

      test("merge: case one parent already deleted never fires") {
        val removed = Vector(DiffLine(9, "    test(\"second\") {"))
        val legs    = Vector(
          ParentLeg(testPath, removed, () => suite),
          ParentLeg(
            testPath,
            Vector.empty,
            () => suite.replace("    test(\"second\") {\n      assert(3 == 3)\n    }\n", "")
          )
        )
        val cs      = Vector(ChangedFile(Some(testPath), legs, binary = false, added = Vector.empty))
        assert(testProtection.appliesTo(cs).isEmpty)
      }
    }
  }
}

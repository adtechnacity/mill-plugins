package atn.mill

import mill.*
import mill.api.{Discover, ExecResult, ModuleRef}
import mill.scalalib.*
import mill.testkit.{TestRootModule, UnitTester}
import utest.*

/**
 * Drives [[CpdSupport]] through Mill's `UnitTester` over the example workspace, whose `a/src/Dup.scala` and
 * `b/src/Dup.scala` share one block of roughly 240 tokens (and nothing else of 25 tokens or more).
 */
object CpdSupportTest extends TestSuite:

  private def exampleWorkspace: os.Path = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "example-cpd"

  // The fixed columns of every cpd-<language>.csv row; the (line, file) pair of each occurrence follows them.
  private val csvHeader = "lines,tokens,occurrences"

  private def reportDir(root: TestRootModule): os.Path = root.moduleDir / "out" / "cpd" / "cpdCheckAll.dest"

  private def value[T](result: Either[ExecResult.Failing[T], UnitTester.Result[T]]): T = result match
    case Right(r)   => r.value
    case Left(fail) => throw new java.lang.AssertionError(s"Expected success but got $fail")

  private def failureMessage[T](result: Either[ExecResult.Failing[T], UnitTester.Result[T]]): String = result match
    case Left(f: ExecResult.Failure[?]) => f.msg
    case other                          => throw new java.lang.AssertionError(s"Expected a Result.Failure but got $other")

  /** The failure message `cpdCheckAll` produces for `build` over the example workspace. */
  private def checkAllFailure(build: CpdRoot): String =
    UnitTester(build, exampleWorkspace).scoped(eval => failureMessage(eval(build.cpd.cpdCheckAll)))

  val tests = Tests:

    test("defaults - every knob has the documented default and cpdCheckAll is the default task") {
      val cpd = new DefaultBuild().cpd
      assert(cpd.defaultTask() == "cpdCheckAll")
      assert(cpd.pmdVersion == "7.27.0")
      assert(cpd.cpdLanguages == Seq("scala", "java"))
      assert(cpd.cpdMinimumTokens == 25)
      assert(cpd.cpdErrorTokens == 75)
      assert(cpd.cpdExcludes == Seq.empty)
      assert(cpd.cpdOptions == Seq.empty)
    }

    test("cpdModules - discovers every JavaModule under the root, not the cpd module itself") {
      val build = new DefaultBuild()
      assert(build.cpd.cpdModules.toSet == Set[Module](build.a, build.b))
    }

    test("cpdMvnDeps - one PMD language module per language at pmdVersion (scala -> pmd-scala_2.13)") {
      val build = new DefaultBuild()
      UnitTester(build, exampleWorkspace).scoped { eval =>
        val deps = value(eval(build.cpd.cpdMvnDeps))
        assert(deps.map(_.dep.module.name.value) == Seq("pmd-scala_2.13", "pmd-java"))
        assert(deps.forall(_.dep.module.organization.value == "net.sourceforge.pmd"))
        assert(deps.forall(_.toString.contains("7.27.0")))
      }
    }

    test("cpdCheckAll - a cross-module duplicate at or above cpdErrorTokens fails the task") {
      val msg = checkAllFailure(new DefaultBuild())
      assert(msg.contains("CPD (scala): 1 duplication(s) at or above 75 tokens"))
      assert(msg.contains("a/src/Dup.scala"))
      assert(msg.contains("b/src/Dup.scala"))
    }

    test(
      "cpdCheckAll - below cpdErrorTokens the duplicate is a warning, the value is the count, one CSV per language"
    ) {
      val build = new LenientBuild()
      UnitTester(build, exampleWorkspace).scoped { eval =>
        assert(value(eval(build.cpd.cpdCheckAll)) == 1)
        val scala = os.read.lines(reportDir(build) / "cpd-scala.csv")
        val java  = os.read.lines(reportDir(build) / "cpd-java.csv")
        assert(scala.head == csvHeader)
        assert(scala.size == 2)
        assert(scala(1).split(',')(1).toInt >= 200)
        assert(java == Seq(csvHeader))
      }
    }

    test("cpdCheckAll - the warning count is cached until an input changes") {
      val build = new LenientBuild()
      UnitTester(build, exampleWorkspace).scoped { eval =>
        val first  = eval(build.cpd.cpdCheckAll)
        val second = eval(build.cpd.cpdCheckAll)
        assert(value(first) == 1)
        assert(second == Right(UnitTester.Result(1, 0)))
      }
    }

    test("cpdExcludes - a directory relative to the workspace root is pruned from the scan") {
      // Control: without the exclude the generated copy is scanned (whole-file duplicate + three-way block).
      val control = new LenientBuild()
      UnitTester(control, exampleWorkspace).scoped { eval =>
        val generated = control.moduleDir / "b" / "src" / "generated" / "Generated.scala"
        os.write(generated, os.read(control.moduleDir / "b" / "src" / "Dup.scala"), createFolders = true)
        assert(value(eval(control.cpd.cpdCheckAll)) == 2)
      }
      val build   = new ExcludesBuild()
      UnitTester(build, exampleWorkspace).scoped { eval =>
        val generated = build.moduleDir / "b" / "src" / "generated" / "Generated.scala"
        os.write(generated, os.read(build.moduleDir / "b" / "src" / "Dup.scala"), createFolders = true)
        assert(value(eval(build.cpd.cpdCheckAll)) == 1)
        val rows      = os.read.lines(reportDir(build) / "cpd-scala.csv").drop(1)
        assert(rows.size == 1)
        assert(rows.head.split(',')(2) == "2")
        assert(!rows.head.contains("generated"))
      }
    }

    test("CPD-OFF - a copy fenced with the in-source marker is not reported") {
      val build = new DefaultBuild()
      UnitTester(build, exampleWorkspace).scoped { eval =>
        val copy = build.moduleDir / "b" / "src" / "Dup.scala"
        os.write.over(copy, "// CPD-OFF\n" + os.read(copy))
        assert(value(eval(build.cpd.cpdCheckAll)) == 0)
      }
    }

    test("cpdModules - dropping a module removes its sources from the scan") {
      val build = new OnlyABuild()
      UnitTester(build, exampleWorkspace).scoped { eval =>
        assert(build.cpd.cpdModules == Seq(build.a))
        assert(value(eval(build.cpd.cpdCheckAll)) == 0)
      }
    }

    test("cpdLanguages - only the configured languages run, resolve and report") {
      val build = new JavaOnlyBuild()
      UnitTester(build, exampleWorkspace).scoped { eval =>
        assert(value(eval(build.cpd.cpdMvnDeps)).map(_.dep.module.name.value) == Seq("pmd-java"))
        assert(value(eval(build.cpd.cpdCheckAll)) == 0)
        assert(os.exists(reportDir(build) / "cpd-java.csv"))
        assert(!os.exists(reportDir(build) / "cpd-scala.csv"))
      }
    }

    test("cpdCheckAll - no source directories at all is 0 without running CPD") {
      val build = new DefaultBuild()
      UnitTester(build, os.temp.dir()).scoped { eval =>
        assert(value(eval(build.cpd.cpdCheckAll)) == 0)
        assert(!os.exists(reportDir(build) / "cpd-scala.csv"))
        assert(!os.exists(reportDir(build) / "cpd-java.csv"))
      }
    }

    test("cpdErrorTokens below cpdMinimumTokens is a configuration failure") {
      val msg = checkAllFailure(new BadThresholdsBuild())
      assert(msg.contains("cpdErrorTokens"))
      assert(msg.contains("cpdMinimumTokens"))
    }

    test("cpdOptions - an unknown PMD flag fails with PMD's own usage error and no stack trace") {
      val msg = checkAllFailure(new BadOptionsBuild())
      assert(msg.contains("CPD (scala) exited 2"))
      assert(msg.contains("Unknown option: '--no-such-flag'"))
      assert(!msg.contains("\tat "))
    }

// --- Fixtures: the shape of the example workspace's build.mill, one class per configuration ---

// The modules are nested in classes rather than in objects: Scala 3 compiles objects nested in an object to
// static fields that Mill's reflective child discovery does not see, whereas a real build.mill is wrapped in a
// class by Mill's codegen and reflects fine. Each test instantiates its fixture, so every UnitTester gets a
// fresh module directory.
abstract class CpdRoot extends TestRootModule:
  /** The configuration under test; each fixture nests its own `object cpd`. */
  def cpd: CpdSupport
  object a extends ScalaModule:
    def scalaVersion = "3.8.4"
  object b extends ScalaModule:
    def scalaVersion = "3.8.4"

class DefaultBuild extends CpdRoot:
  object cpd extends CpdSupport:
    def cpdRootModule = ModuleRef(DefaultBuild.this)
  lazy val millDiscover: Discover = Discover[this.type]

class LenientBuild extends CpdRoot:
  object cpd extends CpdSupport:
    def cpdRootModule           = ModuleRef(LenientBuild.this)
    override def cpdErrorTokens = 500
  lazy val millDiscover: Discover = Discover[this.type]

class ExcludesBuild extends CpdRoot:
  object cpd extends CpdSupport:
    def cpdRootModule           = ModuleRef(ExcludesBuild.this)
    override def cpdErrorTokens = 500
    override def cpdExcludes    = Seq("b/src/generated")
  lazy val millDiscover: Discover = Discover[this.type]

class OnlyABuild extends CpdRoot:
  object cpd extends CpdSupport:
    def cpdRootModule       = ModuleRef(OnlyABuild.this)
    override def cpdModules = super.cpdModules.filterNot(_ == b)
  lazy val millDiscover: Discover = Discover[this.type]

class JavaOnlyBuild extends CpdRoot:
  object cpd extends CpdSupport:
    def cpdRootModule           = ModuleRef(JavaOnlyBuild.this)
    override def cpdLanguages   = Seq("java")
    override def cpdErrorTokens = 500
  lazy val millDiscover: Discover = Discover[this.type]

class BadThresholdsBuild extends CpdRoot:
  object cpd extends CpdSupport:
    def cpdRootModule           = ModuleRef(BadThresholdsBuild.this)
    override def cpdErrorTokens = 10
  lazy val millDiscover: Discover = Discover[this.type]

class BadOptionsBuild extends CpdRoot:
  object cpd extends CpdSupport:
    def cpdRootModule           = ModuleRef(BadOptionsBuild.this)
    override def cpdErrorTokens = 500
    override def cpdOptions     = Seq("--no-such-flag")
  lazy val millDiscover: Discover = Discover[this.type]

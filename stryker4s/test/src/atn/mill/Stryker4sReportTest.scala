package atn.mill

import mill.*
import mill.api.{Discover, PathRef, Result, SelectMode}
import mill.testkit.{TestRootModule, UnitTester}
import utest.*

import StrykerTestSupport.evalOrFail

object Stryker4sReportTest extends TestSuite:

  private val json = "report.json"
  private val html = "index.html"

  /**
   * Seed the report dirs a `strykerMutate` run leaves for `module`, one per `(stamp, files)`, with rising mtimes so the
   * last one is the newest.
   */
  private def seedReports(root: os.Path, module: String, reports: Seq[(String, Map[String, String])]): Seq[os.Path] =
    reports.zipWithIndex.map { case ((stamp, files), i) =>
      val dir = root / "out" / module / "strykerMutate.dest" / "target" / "stryker4s-report" / stamp
      files.foreach((name, content) => os.write(dir / name, content, createFolders = true))
      os.mtime.set(dir, 1_700_000_000_000L + i * 60_000L)
      dir
    }

  private def destOf(result: UnitTester.Result[Seq[?]]): os.Path = result.value.head.asInstanceOf[PathRef].path

  val tests = Tests:

    test("report tasks resolve the newest run; the aggregates copy what each module has") {
      UnitTester(StrykerReportBuild, os.temp.dir()).scoped { eval =>
        val root           = StrykerReportBuild.moduleDir
        val Seq(_, newest) = seedReports(
          root,
          "example",
          Seq(
            "20240101" -> Map(json -> "{\"old\":1}", html -> "<p>old</p>"),
            "20240102" -> Map(json -> "{\"new\":1}", html -> "<p>new</p>")
          )
        )
        seedReports(root, "htmlonly", Seq("20240103" -> Map(html -> "<p>html only</p>")))
        seedReports(root, "jsononly", Seq("20240104" -> Map(json -> "{\"json\":1}")))

        val Right(jsonReport) = eval(StrykerReportBuild.example.strykerJsonReport): @unchecked
        assert(jsonReport.value.path == newest / json)
        val Right(htmlReport) = eval(StrykerReportBuild.example.strykerHtmlReport): @unchecked
        assert(htmlReport.value.path == newest / html)

        // One <module>.json per module that has a JSON report; a module without one is skipped, not an error.
        val jsonDest = destOf(evalOrFail(eval, "stryker4s.jsonReportAll"))
        assert(os.read(jsonDest / "example.json") == "{\"new\":1}")
        assert(os.read(jsonDest / "jsononly.json") == "{\"json\":1}")
        assert(!os.exists(jsonDest / "htmlonly.json"))

        // The whole newest report dir of each module lands under <module>/; a module without HTML gets an empty dir.
        val htmlDest = destOf(evalOrFail(eval, "stryker4s.htmlReportAll"))
        assert(os.read(htmlDest / "example" / html) == "<p>new</p>")
        assert(os.read(htmlDest / "example" / json) == "{\"new\":1}")
        assert(os.read(htmlDest / "htmlonly" / html) == "<p>html only</p>")
        assert(os.isDir(htmlDest / "jsononly"))
        assert(os.list(htmlDest / "jsononly").isEmpty)

        // Reports print to the console, so the commands run exclusively.
        assert(StrykerReportBuild.stryker4s.jsonReportAll(eval.evaluator).exclusive)
        assert(StrykerReportBuild.stryker4s.htmlReportAll(eval.evaluator).exclusive)
      }
    }

    test("runAll - the default task; nothing to mutate in a build without Stryker4sModules is not a failure") {
      assert(BareReportBuild.stryker4s.defaultTask() == "runAll")
      UnitTester(BareReportBuild, os.temp.dir()).scoped { eval =>
        assert(eval.evaluator.resolveTasks(Seq("__.strykerMutate"), SelectMode.Multi).isInstanceOf[Result.Failure])
        evalOrFail(eval, "stryker4s.runAll")
        assert(BareReportBuild.stryker4s.runAll(eval.evaluator).exclusive)
      }
    }

// The modules are nested in a class rather than directly in the root object: Scala 3 compiles objects nested in an
// object to static fields that Mill's reflective child discovery does not see.
abstract class StrykerReportRoot extends TestRootModule:
  object example  extends TestStrykerModule
  object htmlonly extends TestStrykerModule
  object jsononly extends TestStrykerModule

  object stryker4s extends Stryker4sReport

object StrykerReportBuild extends StrykerReportRoot:
  lazy val millDiscover: Discover = Discover[this.type]

abstract class BareReportRoot extends TestRootModule:
  object stryker4s extends Stryker4sReport

object BareReportBuild extends BareReportRoot:
  lazy val millDiscover: Discover = Discover[this.type]

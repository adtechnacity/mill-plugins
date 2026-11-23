package atn.mill
import org.eclipse.jgit.api.Git

import mill.*
import mill.api.Discover
import mill.scalalib.*
import mill.testkit.{TestRootModule, UnitTester}
import utest.*

import UnitTesterSupport.{failureOf, value, withBuild}

object CodeSceneModuleTest extends TestSuite:

  /** Writes a fake `cs` script into `dir` that echoes canned JSON scores per filename. */
  private def fakeCs(dir: os.Path, scoresBySuffix: Map[String, Double]): os.Path =
    val cases  = scoresBySuffix
      .map { (suffix, score) =>
        s"""  *$suffix) echo '{"path":"'"$$f"'","score":$score,"review":[{"category":"Test Smell","description":"desc","indication":1}]}' ;;"""
      }
      .mkString("\n")
    val script =
      s"""#!/bin/sh
         |file_list=""
         |while [ $$# -gt 0 ]; do
         |  case "$$1" in
         |    --file-list) file_list="$$2"; shift 2 ;;
         |    *) shift ;;
         |  esac
         |done
         |printf '['
         |first=1
         |while IFS= read -r f; do
         |  [ -z "$$f" ] && continue
         |  if [ $$first -eq 0 ]; then printf ','; fi
         |  first=0
         |  case "$$f" in
         |$cases
         |    *) echo '{"path":"'"$$f"'","score":10.0,"review":[]}' ;;
         |  esac
         |done < "$$file_list"
         |printf ']'
         |""".stripMargin
    val bin    = dir / "fake-cs"
    os.write.over(bin, script, perms = "rwxr-xr-x")
    bin

  abstract class BaseBuild(csBin: os.Path) extends TestRootModule:
    object app extends ScalaModule with CodeSceneModule:
      def scalaVersion              = "3.9.0"
      override def csCli            = csBin.toString
      override def generatedSources = Task {
        val gen = Task.dest / "Generated.scala"
        os.write.over(gen, "object Generated")
        Seq(PathRef(Task.dest))
      }

      object test extends ScalaTests:
        def testFramework = "utest.runner.Framework"

  class DefaultBuild(csBin: os.Path) extends BaseBuild(csBin):
    lazy val millDiscover = Discover[this.type]

  class StrictProdLenientTestBuild(csBin: os.Path) extends TestRootModule:
    object app extends ScalaModule with CodeSceneModule:
      def scalaVersion                     = "3.9.0"
      override def csCli                   = csBin.toString
      override def codeSceneProdWarnScore  = 9.5
      override def codeSceneProdErrorScore = 9.0
      override def codeSceneTestWarnScore  = 8.0
      override def codeSceneTestErrorScore = 7.0

      object test extends ScalaTests:
        def testFramework = "utest.runner.Framework"

    lazy val millDiscover = Discover[this.type]

  class BadThresholdsBuild extends TestRootModule:
    object app extends ScalaModule with CodeSceneModule:
      def scalaVersion                     = "3.9.0"
      override def codeSceneProdWarnScore  = 7.0
      override def codeSceneProdErrorScore = 8.0
    lazy val millDiscover = Discover[this.type]

  class UnsetTokenBuild extends TestRootModule:
    object app extends ScalaModule with CodeSceneModule:
      def scalaVersion                  = "3.9.0"
      override def codeSceneTokenEnvVar = "MILL_CODESCENE_UNSET_TOKEN_FOR_TEST"
    lazy val millDiscover = Discover[this.type]

  class UploadBuild(baseUrl: String, report: os.Path) extends TestRootModule:
    object app extends ScalaModule with CodeSceneModule:
      def scalaVersion                     = "3.9.0"
      override def codeSceneApiUrl         = baseUrl
      override def codeSceneTokenEnvVar    = "PATH" // guaranteed non-empty in Task.env
      override def codeSceneCoverageReport = Task(PathRef(report))
    lazy val millDiscover = Discover[this.type]

  val tests = Tests:

    test("codeSceneAnalyze - excludes generatedSources and evaluates prod vs test with separate thresholds"):
      val ws  = os.temp.dir()
      val bin = fakeCs(ws, Map("Prod.scala" -> 9.2, "Spec.scala" -> 7.5, "Generated.scala" -> 2.0))
      os.write(ws / "app" / "src" / "Prod.scala", "object Prod", createFolders = true)
      os.write(ws / "app" / "test" / "src" / "Spec.scala", "object Spec", createFolders = true)

      withBuild(new StrictProdLenientTestBuild(bin), ws) { (build, eval) =>
        val warningCount = value(eval, build.app.codeSceneAnalyze)
        // Prod.scala (9.2) is below prod warn 9.5 (above prod error 9.0) -> 1 warning
        // Spec.scala (7.5) is below test warn 8.0 (above test error 7.0) -> 1 warning
        // Generated.scala (2.0) was never scanned
        assert(warningCount == 2)
        val report       = os.read(build.moduleDir / "out" / "app" / "codeSceneAnalyze.dest" / "codescene-review.json")
        assert(report.contains("Prod.scala"))
        assert(report.contains("Spec.scala"))
        assert(!report.contains("Generated.scala"))
      }

    test("codeSceneAnalyze - fails when prod file drops below prod error threshold"):
      val ws  = os.temp.dir()
      val bin = fakeCs(ws, Map("Prod.scala" -> 8.5, "Spec.scala" -> 7.5))
      os.write(ws / "app" / "src" / "Prod.scala", "object Prod", createFolders = true)
      os.write(ws / "app" / "test" / "src" / "Spec.scala", "object Spec", createFolders = true)

      withBuild(new StrictProdLenientTestBuild(bin), ws) { (build, eval) =>
        val msg = failureOf(eval(build.app.codeSceneAnalyze))
        assert(msg.contains("Prod.scala"))
        assert(msg.contains("9.0"))
      }

    test("codeSceneAnalyze - fails when test file drops below test error threshold"):
      val ws  = os.temp.dir()
      val bin = fakeCs(ws, Map("Prod.scala" -> 9.8, "Spec.scala" -> 6.5))
      os.write(ws / "app" / "src" / "Prod.scala", "object Prod", createFolders = true)
      os.write(ws / "app" / "test" / "src" / "Spec.scala", "object Spec", createFolders = true)

      withBuild(new StrictProdLenientTestBuild(bin), ws) { (build, eval) =>
        val msg = failureOf(eval(build.app.codeSceneAnalyze))
        assert(msg.contains("Spec.scala"))
        assert(msg.contains("7.0"))
      }

    test("codeSceneAnalyze - returns 0 without invoking cs when module has no hand-written source files"):
      val ws = os.temp.dir()
      withBuild(new DefaultBuild(ws / "nonexistent-cs"), ws) { (build, eval) =>
        assert(value(eval, build.app.codeSceneAnalyze) == 0)
      }

    test("codeSceneWriteRules - writes .codescene/code-health-rules.json with test rules before prod rules"):
      val ws = os.temp.dir()
      os.write(ws / "app" / "src" / "Prod.scala", "object Prod", createFolders = true)
      os.write(ws / "app" / "test" / "src" / "Spec.scala", "object Spec", createFolders = true)

      withBuild(new DefaultBuild(ws / "unused"), ws) { (build, eval) =>
        value(eval, build.app.codeSceneWriteRules())
        val rulesPath = build.moduleDir / ".codescene" / "code-health-rules.json"
        assert(os.exists(rulesPath))
        val parsed    = ujson.read(os.read(rulesPath))
        val paths     = parsed("rule_sets").arr.map(_("matching_content_path").str).toList
        assert(paths.head == "app/test/src/**")
      }

    test("codeSceneAnalyze - rejects invalid thresholds before running cs"):
      val ws = os.temp.dir()
      withBuild(new BadThresholdsBuild, ws) { (build, eval) =>
        val msg = failureOf(eval(build.app.codeSceneAnalyze))
        assert(msg.contains("errorScore"))
        assert(msg.contains("warnScore"))
      }

    test("codeSceneUploadCoverage - fails clearly when token env var is unset"):
      val ws = os.temp.dir()
      withBuild(new UnsetTokenBuild, ws) { (build, eval) =>
        val msg = failureOf(eval(build.app.codeSceneUploadCoverage()))
        assert(msg.contains("MILL_CODESCENE_UNSET_TOKEN_FOR_TEST"))
      }

    test("codeSceneUploadCoverage - reads git metadata and uploads report over HTTP"):
      val ws      = os.temp.dir()
      val git     = Git.init().setDirectory(ws.toIO).setInitialBranch("main").call()
      val cfg     = git.getRepository.getConfig
      cfg.setString("remote", "origin", "url", "https://github.com/example/sample.git")
      cfg.save()
      val headSha = git
        .commit()
        .setAllowEmpty(true)
        .setSign(false)
        .setAuthor("Test", "test@example.com")
        .setCommitter("Test", "test@example.com")
        .setMessage("init")
        .call()
        .getId
        .name
      val report  = ws / "cobertura.xml"
      os.write.over(report, "<coverage/>")

      CodeSceneCoverageUploaderTest.withFakeServer {
        case r if r.method == "POST" && r.path == "/v2/code-coverage/upload"      =>
          (200, """{"ref":"/v2/code-coverage/upload/run-1"}""")
        case r if r.method == "PUT" && r.path == "/v2/code-coverage/upload/run-1" =>
          (200, """{"ok":true}""")
      } { (baseUrl, requests) =>
        withBuild(new UploadBuild(baseUrl, report), ws) { (build, eval) =>
          val ref = value(eval, build.app.codeSceneUploadCoverage())
          assert(ref == "/v2/code-coverage/upload/run-1")
        }
        val meta = ujson.read(requests().head.bodyText)
        assert(meta("commit-sha").str == headSha)
        assert(meta("repo-url").str == "https://github.com/example/sample.git")
        assert(meta("format").str == "cobertura")
      }

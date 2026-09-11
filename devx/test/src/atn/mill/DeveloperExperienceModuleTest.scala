package atn.mill

import utest.*
import upickle.{default => json}
import mill.api.{Discover, ExecResult, Task}
import mill.testkit.{TestRootModule, UnitTester}
import org.scalacheck.Prop.{forAll, propBoolean}
import PropertyChecks.checkProp
import FakeApi.*
import DevxFixtures.*

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8

/**
 * Drives the [[DeveloperExperience]] commands through Mill's `UnitTester` against [[FakeApi]] stand-ins for CodeScene
 * and Port.io.
 */
object DeveloperExperienceModuleTest extends TestSuite:
  import DeveloperExperience.*

  /** A test's build, evaluator, fake services and captured Mill log. */
  final private case class Scope(build: DevxBuild, eval: UnitTester, api: FakeApi, out: ByteArrayOutputStream):
    def log: String        = out.toString(UTF_8)
    def workspace: os.Path = build.moduleDir

    /** Evaluates `task`, failing the test unless it succeeds. */
    def value[T](task: Task[T]): T = eval(task) match
      case Right(UnitTester.Result(v, _)) => v
      case Left(failure)                  => throw new java.lang.AssertionError(s"$task failed: $failure")

    /** Evaluates `task`, failing the test unless it ends in a `Result.Failure`, whose message is returned. */
    def failure(task: Task[?]): String = eval(task) match
      case Left(ExecResult.Failure(message, _)) => message
      case other                                => throw new java.lang.AssertionError(s"$task did not fail: $other")

  /** Runs `body` in a fresh build whose clients talk to a fake server answering `routes`. */
  private def scoped[A](routes: PartialFunction[Received, Reply])(body: Scope => A): A =
    withClients(routes) { c =>
      val build  = new DevxBuild(c.codeScene, c.portIO)
      val out    = new ByteArrayOutputStream
      val stream = new PrintStream(out, true, UTF_8)
      UnitTester(build, os.temp.dir(), outStream = stream, errStream = stream).scoped { eval =>
        body(Scope(build, eval, c.server, out))
      }
    }

  private val exportDir     = "codescene-export"
  private val projectFiles  = Seq("project.json", "configuration.json", "components.json", "repositories.json")
  private val analysisFiles = Seq(
    "summary.json",
    "files.json",
    "components.json",
    "author-statistics.json",
    "branch-statistics.json",
    "commit-activity.json",
    "skills-inventory.json"
  )

  /** The export under `dest` holds project 3's files and every analysis the fake serves; returns its analysis dir. */
  private def assertProjectExported(dest: os.Path): os.Path =
    val projectDir  = dest / "projects" / "mill-plugins"
    val analysisDir = projectDir / "analysis"
    assert(ujson.read(os.read(projectDir / "project.json")) == project)
    projectFiles.foreach(file => assert(os.exists(projectDir / file)))
    analysisFiles.foreach(file => assert(os.exists(analysisDir / file)))
    val files       = ujson.read(os.read(analysisDir / "files.json"))
    assert(files == ujson.Obj("path" -> "/cs/projects/3/analyses/latest/files"))
    analysisDir

  private val bulkRejected: PartialFunction[Received, Reply] =
    case Route("POST", "/port/blueprints/code_scene_teams/entities/bulk") => Reply(304, "")

  private val technicalDebt = ujson.Obj("debt" -> 0)

  private val technicalDebtServed: PartialFunction[Received, Reply] =
    case Route("GET", "/cs/projects/3/analyses/latest/technical-debt") => Reply.json(technicalDebt)

  private val noProjects: PartialFunction[Received, Reply] =
    case Route("GET", "/cs/projects") => Reply.json(ujson.Obj())

  private def developer(id: Int, teamName: String, former: Boolean): ujson.Obj =
    ujson.Obj("id" -> id, "team_name" -> teamName, "former_contributor" -> former)

  val tests = Tests:

    test("devSettings - is the All-of-Adtechnacity configuration"):
      scoped(services)(s => assert(s.build.devx.devSettings == allOfAtn))

    test("upsertTeams"):

      test("upserts every CodeScene team as a Port entity"):
        scoped(services) { s =>
          s.value(s.build.devx.upsertTeams())
          val upload = s.api.requests.last
          assert(upload.path == "/port/blueprints/code_scene_teams/entities/bulk", upload.query == Some("upsert=true"))
          val sent   = json.read[List[PortIO.Entity]](upload.json("entities"))
          assert(sent == teams.map(team => PortIO.Entity(team.id.toString, team.name)))
          assert(upload.headers("authorization") == s"Bearer ${token.accessToken}")
        }

      test("fails with the Port reply when the upload is not accepted"):
        scoped(bulkRejected.orElse(services)) { s =>
          val message = s.failure(s.build.devx.upsertTeams())
          assert(message.startsWith("Failed to upsert Teams."))
        }

    test("developerList - logs every developer and the total"):
      scoped(services) { s =>
        s.value(s.build.devx.developerList())
        developers.foreach(dev => assert(s.log.contains(formatDeveloper(dev))))
        assert(s.log.contains("Total: 2 developers"))
      }

    test("exportAll - writes the developer settings and every project under the default workspace directory"):
      scoped(services) { s =>
        s.value(s.build.devx.exportAll())
        val dest        = s.workspace / exportDir
        assert(ujson.read(os.read(dest / "developer-settings.json"))("developer_settings").arr.size == 2)
        for setting <- settings do
          val dir = dest / "developer-settings" / slugify(setting.name)
          assert(ujson.read(os.read(dir / "teams.json"))("teams") == json.writeJs(teams))
          assert(ujson.read(os.read(dir / "developers.json"))("developers") == json.writeJs(developers))
        assert(ujson.read(os.read(dest / "projects.json")) == projectList)
        val analysisDir = assertProjectExported(dest)
        assert(!os.exists(analysisDir / "technical-debt.json")) // the fake has none: skipped with a warning
        assert(s.log.contains("Export complete: 2 developer settings, 1 projects"))
      }

    test("exportProject - writes one project's data, technical debt included, into the given directory"):
      scoped(technicalDebtServed.orElse(services)) { s =>
        s.value(s.build.devx.exportProject(projectId, "exports/one"))
        val dest        = s.workspace / "exports" / "one"
        val analysisDir = assertProjectExported(dest)
        assert(ujson.read(os.read(analysisDir / "technical-debt.json")) == technicalDebt)
        assert(!os.exists(dest / "projects.json"))
        assert(s.log.contains(s"Export complete: project '$projectName' (id=$projectId)"))
      }

    test("exportProjectList - writes projects.json and lists nothing when the payload has no projects"):
      scoped(noProjects) { s =>
        val dest = os.temp.dir()
        assert(s.build.devx.exportProjectList(dest) == Nil, ujson.read(os.read(dest / "projects.json")) == ujson.Obj())
      }

    test("importProject"):

      test("posts the configuration file to projects/new"):
        scoped(services) { s =>
          val config  = ujson.Obj("name" -> "Imported")
          os.write(s.workspace / "config.json", ujson.write(config))
          s.value(s.build.devx.importProject("config.json"))
          val request = s.api.requests.last
          assert(request.method == "POST", request.path == "/cs/projects/new", request.json == config)
          assert(s.log.contains("Import result:"))
        }

      test("fails when the file is missing"):
        scoped(services) { s =>
          assert(s.failure(s.build.devx.importProject("missing.json")).contains("Config file not found"))
        }

    test("importTeams"):

      test("creates the missing teams and reassigns the developers of known teams"):
        scoped(services) { s =>
          val teamsJson =
            ujson.Obj("teams" -> ujson.Arr(Seq("Platform", "Data", "Research").map(n => ujson.Obj("name" -> n))*))
          val devsJson  =
            ujson.Obj("developers" -> ujson.Arr(developer(5, "Platform", false), developer(6, "Research", true)))
          os.write(s.workspace / "teams.json", ujson.write(teamsJson))
          os.write(s.workspace / "developers.json", ujson.write(devsJson))
          s.value(s.build.devx.importTeams("teams.json", allOfAtn.id))
          val writes    = s.api.requests.filter(_.method != "GET").map(r => (r.method, r.path, r.json))
          val expected  = List(
            ("POST", "/cs/developer-settings/7/teams/new", ujson.Obj("name" -> "Research")),
            ("PUT", "/cs/developer-settings/7/developers/5", ujson.Obj("team_id" -> 2, "former_contributor" -> false))
          )
          assert(writes == expected)
          assert(
            s.log.contains("Created 1 new teams (2 already existed)"),
            s.log.contains("Updated 1 developer assignments")
          )
        }

      test("fails when the teams file is missing"):
        scoped(services) { s =>
          assert(s.failure(s.build.devx.importTeams("teams.json", 7)).contains("Teams file not found"))
        }

      test("fails when developers.json is missing beside the teams file"):
        scoped(services) { s =>
          os.write(s.workspace / "teams.json", """{"teams": []}""")
          assert(s.failure(s.build.devx.importTeams("teams.json", 7)).contains("Developers file not found"))
        }

    test("formatDeveloper - lists name, team, email and the former-contributor flag"):
      checkProp(forAll(CodeSceneTest.genDeveloper) { dev =>
        val fields   = formatDeveloper(dev).split(" \\| ", -1).toList
        val expected =
          List(dev.name, s"team=${dev.team_name}", s"email=${dev.email}", s"former=${dev.former_contributor}")
        (fields == expected).label(fields.mkString(" / "))
      })

    test("external module"):

      test("runs upsertTeams by default"):
        assert(DeveloperExperience.defaultTask() == "upsertTeams")

      test("talks to the public CodeScene and Port.io deployments by default"):
        assert(DeveloperExperience.codeScene == CodeScene, DeveloperExperience.portIO == PortIO)

// The modules are nested in a class rather than an object: Scala 3 compiles objects nested in an object to static
// fields that Mill's reflective child discovery does not see, whereas a real build.mill is wrapped in a class by
// Mill's codegen and reflects fine. Each test instantiates its own build, so every UnitTester gets a fresh module dir
// and the module talks to that test's fake clients.
abstract class DevxRoot(codeSceneClient: CodeSceneClient, portIOClient: PortIOClient) extends TestRootModule:
  object devx extends DeveloperExperience:
    def defaultTask(): String               = "upsertTeams"
    override def codeScene: CodeSceneClient = codeSceneClient
    override def portIO: PortIOClient       = portIOClient

class DevxBuild(codeSceneClient: CodeSceneClient, portIOClient: PortIOClient)
    extends DevxRoot(codeSceneClient, portIOClient):
  lazy val millDiscover: Discover = Discover[this.type]

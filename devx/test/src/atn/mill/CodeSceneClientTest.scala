package atn.mill

import utest.*
import FakeApi.*
import DevxFixtures.{codeScene, developers, echo, settings, teams}

/** [[CodeScene]] against a [[FakeApi]]: authentication, the request each endpoint issues and the JSON it returns. */
object CodeSceneClientTest extends TestSuite:

  private val config = ujson.Obj("name" -> "Imported", "repositories" -> ujson.Arr("git@example.com:repo.git"))

  /** Every GET endpoint as (call, path under the API root). */
  private val gets: Seq[(() => ujson.Value, String)] = Seq(
    (() => CodeScene.devSettingsRaw, "/developer-settings"),
    (() => CodeScene.teamsRaw(7), "/developer-settings/7/teams"),
    (() => CodeScene.developersRaw(7), "/developer-settings/7/developers"),
    (() => CodeScene.projects, "/projects"),
    (() => CodeScene.project(3), "/projects/3"),
    (() => CodeScene.projectConfig(3), "/projects/3/export/configuration/json"),
    (() => CodeScene.components(3), "/projects/3/components"),
    (() => CodeScene.repositories(3), "/projects/3/repositories"),
    (() => CodeScene.latestAnalysis(3), "/projects/3/analyses/latest"),
    (() => CodeScene.fileMetrics(3), "/projects/3/analyses/latest/files"),
    (() => CodeScene.componentAnalysis(3), "/projects/3/analyses/latest/components"),
    (() => CodeScene.authorStats(3), "/projects/3/analyses/latest/author-statistics"),
    (() => CodeScene.branchStats(3), "/projects/3/analyses/latest/branch-statistics"),
    (() => CodeScene.technicalDebt(3), "/projects/3/analyses/latest/technical-debt"),
    (() => CodeScene.commitActivity(3), "/projects/3/analyses/latest/commit-activity"),
    (() => CodeScene.skillsInventory(3), "/projects/3/analyses/latest/experience/languages")
  )

  /** Every write endpoint as (call, method, path under the API root, JSON body it must send). */
  private val writes: Seq[(() => ujson.Value, String, String, ujson.Value)] = Seq(
    (() => CodeScene.importProjectConfig(config), "POST", "/projects/new", config),
    (
      () => CodeScene.createTeam(7, "Research"),
      "POST",
      "/developer-settings/7/teams/new",
      ujson.Obj("name" -> "Research")
    ),
    (() => CodeScene.updateTeam(7, 2, "Core"), "PUT", "/developer-settings/7/teams/2", ujson.Obj("name" -> "Core")),
    (
      () => CodeScene.updateDeveloper(7, 5, 2, true),
      "PUT",
      "/developer-settings/7/developers/5",
      ujson.Obj("team_id" -> 2, "former_contributor" -> true)
    )
  )

  /** Echoes every request as `{"path": ...}`, whatever its method. */
  private val echoAll: PartialFunction[Received, Reply] = { case request => echo(request) }

  /**
   * Runs `call` against the echo server: it must issue exactly one authenticated `method` request to `path` under the
   * API root and return the echo; the request then goes to `check`.
   */
  private def served(call: () => ujson.Value, method: String, path: String)(check: Received => Unit): Unit =
    withClients(echoAll) { server =>
      val result = call()
      server.requests match
        case List(request) =>
          assert(request.method == method, request.path == s"/cs$path", result("path").str == s"/cs$path")
          assert(request.headers("authorization") == s"Bearer $CodeSceneToken")
          assert(request.headers("accept") == "application/json")
          check(request)
        case other         => throw new java.lang.AssertionError(s"expected one request, got $other")
    }

  val tests = Tests:

    test("token"):

      test("fails naming the variable when it is unset or empty"):
        for value <- Seq(None, Some("")) do
          withClients(echoAll) { _ =>
            CodeScene.getenv = _ => value
            val error = assertThrows[RuntimeException](CodeScene.token)
            assert(error.getMessage.contains(CodeScene.csAccessTokenEnvVar))
          }

      test("getenv reads the process environment by default"):
        val absent = "ATN_DEVX_" + java.util.UUID.randomUUID.toString.replace('-', '_')
        assert(CodeScene.getenv(absent).isEmpty)

    test("headers - accept JSON with the bearer token"):
      withClients(echoAll) { _ =>
        assert(CodeScene.headers == Map("Accept" -> "application/json", "Authorization" -> s"Bearer $CodeSceneToken"))
      }

    test("GET endpoints - request their path with the auth headers and return the body"):
      for (call, path) <- gets do served(call, "GET", path)(request => assert(request.body.isEmpty))

    test("typed endpoints - decode their collection from the payload"):
      withClients(codeScene) { server =>
        assert(CodeScene.devSettings == settings)
        assert(CodeScene.teams(7) == teams)
        assert(CodeScene.developers(7) == developers)
        val paths = server.requests.map(_.path)
        assert(
          paths == List(
            "/cs/developer-settings",
            "/cs/developer-settings/7/teams",
            "/cs/developer-settings/7/developers"
          )
        )
      }

    test("write endpoints - send the JSON body with the auth headers and return the reply"):
      for (call, method, path, body) <- writes do
        served(call, method, path) { request =>
          assert(request.json == body, request.headers("content-type") == "application/json")
        }

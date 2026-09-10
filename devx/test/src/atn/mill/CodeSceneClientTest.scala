package atn.mill

import utest.*
import FakeApi.*
import DevxFixtures.{codeScene, developers, echo, settings, teams}

/**
 * [[CodeSceneClient]] against a [[FakeApi]]: authentication, the request each endpoint issues and the JSON it returns.
 */
object CodeSceneClientTest extends TestSuite:

  private val config = ujson.Obj("name" -> "Imported", "repositories" -> ujson.Arr("git@example.com:repo.git"))

  /** Every GET endpoint as (call, path under the API root). */
  private val gets: Seq[(CodeSceneClient => ujson.Value, String)] = Seq(
    (_.devSettingsRaw, "/developer-settings"),
    (_.teamsRaw(7), "/developer-settings/7/teams"),
    (_.developersRaw(7), "/developer-settings/7/developers"),
    (_.projects, "/projects"),
    (_.project(3), "/projects/3"),
    (_.projectConfig(3), "/projects/3/export/configuration/json"),
    (_.components(3), "/projects/3/components"),
    (_.repositories(3), "/projects/3/repositories"),
    (_.latestAnalysis(3), "/projects/3/analyses/latest"),
    (_.fileMetrics(3), "/projects/3/analyses/latest/files"),
    (_.componentAnalysis(3), "/projects/3/analyses/latest/components"),
    (_.authorStats(3), "/projects/3/analyses/latest/author-statistics"),
    (_.branchStats(3), "/projects/3/analyses/latest/branch-statistics"),
    (_.technicalDebt(3), "/projects/3/analyses/latest/technical-debt"),
    (_.commitActivity(3), "/projects/3/analyses/latest/commit-activity"),
    (_.skillsInventory(3), "/projects/3/analyses/latest/experience/languages")
  )

  /** Every write endpoint as (call, method, path under the API root, JSON body it must send). */
  private val writes: Seq[(CodeSceneClient => ujson.Value, String, String, ujson.Value)] = Seq(
    (_.importProjectConfig(config), "POST", "/projects/new", config),
    (_.createTeam(7, "Research"), "POST", "/developer-settings/7/teams/new", ujson.Obj("name" -> "Research")),
    (_.updateTeam(7, 2, "Core"), "PUT", "/developer-settings/7/teams/2", ujson.Obj("name" -> "Core")),
    (
      _.updateDeveloper(7, 5, 2, true),
      "PUT",
      "/developer-settings/7/developers/5",
      ujson.Obj("team_id" -> 2, "former_contributor" -> true)
    )
  )

  /** Echoes every request as `{"path": ...}`, whatever its method. */
  private val echoAll: PartialFunction[Received, Reply] = { case request => echo(request) }

  /**
   * Runs `call` on a client of the echo server: it must issue exactly one authenticated `method` request to `path`
   * under the API root and return the echo; the request then goes to `check`.
   */
  private def served(call: CodeSceneClient => ujson.Value, method: String, path: String)(
    check: Received => Unit
  ): Unit =
    withClients(echoAll) { c =>
      val result = call(c.codeScene)
      c.requests match
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
          val client = new CodeSceneClient:
            override def getEnv(name: String): Option[String] = value
          val error  = assertThrows[RuntimeException](client.token)
          assert(error.getMessage.contains(client.csAccessTokenEnvVar))

      test("getEnv reads the process environment by default"):
        val absent = "ATN_DEVX_" + java.util.UUID.randomUUID.toString.replace('-', '_')
        assert(CodeScene.getEnv(absent).isEmpty)

    test("headers - accept JSON with the bearer token"):
      withClients(echoAll) { c =>
        assert(c.codeScene.headers == Map("Accept" -> "application/json", "Authorization" -> s"Bearer $CodeSceneToken"))
      }

    test("GET endpoints - request their path with the auth headers and return the body"):
      for (call, path) <- gets do served(call, "GET", path)(request => assert(request.body.isEmpty))

    test("typed endpoints - decode their collection from the payload"):
      withClients(codeScene) { c =>
        assert(c.codeScene.devSettings == settings)
        assert(c.codeScene.teams(7) == teams)
        assert(c.codeScene.developers(7) == developers)
        val paths = c.requests.map(_.path)
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

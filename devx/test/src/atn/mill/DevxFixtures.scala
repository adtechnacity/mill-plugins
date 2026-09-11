package atn.mill

import upickle.{default => json}
import CodeScene.{DevSettingsEntry, Developer}

/** Canned CodeScene and Port.io payloads, and the routes serving them behind [[FakeApi.withClients]]. */
object DevxFixtures:

  val contractors = DevSettingsEntry(1, "Contractors", "/developer-settings/1")
  val allOfAtn    = DevSettingsEntry(7, "All-of-Adtechnacity", "/developer-settings/7")

  /** The module picks `All-of-Adtechnacity` by name, so it deliberately is not the first entry. */
  val settings = List(contractors, allOfAtn)

  val platform = DevSettingsEntry(2, "Platform", "/developer-settings/7/teams/2")
  val data     = DevSettingsEntry(3, "Data", "/developer-settings/7/teams/3")
  val teams    = List(platform, data)

  val ada        = Developer(
    5,
    "Ada",
    "Platform",
    "ada@example.com",
    List("ada@example.com"),
    false,
    "/developer-settings/7/developers/5"
  )
  val bob        = Developer(6, "Bob", "Data", "bob@example.com", Nil, true, "/developer-settings/7/developers/6")
  val developers = List(ada, bob)

  val projectId   = 3
  val projectName = "Mill Plugins"
  val project     = ujson.Obj("id" -> projectId, "name" -> projectName)
  val projectList = ujson.Obj("projects" -> ujson.Arr(project))

  val token = PortIO.TokenResponse(ok = true, accessToken = "tok-1", tokenType = "Bearer", expiresIn = 3600)

  /** `{"path": <path>}`, so a test can tell which URL a call hit. */
  def echo(request: Received): Reply = Reply.json(ujson.Obj("path" -> request.path))

  /**
   * CodeScene at `/cs`: the developer-settings tree (the same teams and developers under every setting id), the project
   * list, project 3, and every other request echoed - except project 3's technical-debt analysis, which is missing
   * (404) so an export has one resource to skip.
   */
  val codeScene: PartialFunction[Received, Reply] =
    case Route("GET", "/cs/developer-settings")                        => Reply.json(ujson.Obj("developer_settings" -> json.writeJs(settings)))
    case Route("GET", s"/cs/developer-settings/$id/teams")             => Reply.json(ujson.Obj("teams" -> json.writeJs(teams)))
    case Route("GET", s"/cs/developer-settings/$id/developers")        =>
      Reply.json(ujson.Obj("developers" -> json.writeJs(developers)))
    case Route("GET", "/cs/projects")                                  => Reply.json(projectList)
    case Route("GET", "/cs/projects/3")                                => Reply.json(project)
    case Route("GET", "/cs/projects/3/analyses/latest/technical-debt") => Reply(404, """{"error":"no analysis"}""")
    case request if request.path.startsWith("/cs/")                    => echo(request)

  /** Port.io at `/port`: a token, and an accepting bulk entity upload for the `code_scene_teams` blueprint. */
  val port: PartialFunction[Received, Reply] =
    case Route("POST", "/port/auth/access_token")                         => Reply.json(json.writeJs(token))
    case Route("POST", "/port/blueprints/code_scene_teams/entities/bulk") => Reply(200, """{"ok":true}""")

  /** Both services. */
  val services: PartialFunction[Received, Reply] = codeScene.orElse(port)

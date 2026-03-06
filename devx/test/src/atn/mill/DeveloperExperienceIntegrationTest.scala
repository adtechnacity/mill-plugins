package atn.mill

import utest._

import scala.util.Try

/**
 * Integration tests for DeveloperExperience commands and CodeScene/PortIO clients.
 *
 * These tests exercise the live CodeScene API (requires CS_ACCESS_TOKEN env var). PortIO tests are skipped when
 * PORT_CLIENT_ID / PORT_CLIENT_SECRET are not set.
 */
object DeveloperExperienceIntegrationTest extends TestSuite:

  private def hasCodeSceneToken: Boolean =
    Option(System.getenv("CS_ACCESS_TOKEN")).exists(_.nonEmpty)

  private def hasPortIOCredentials: Boolean =
    Option(System.getenv("PORT_CLIENT_ID")).exists(_.nonEmpty) &&
    Option(System.getenv("PORT_CLIENT_SECRET")).exists(_.nonEmpty)

  val tests = Tests:

    // -- CodeScene API integration tests --

    test("CodeScene"):

      test("token - returns non-empty token when env var is set"):
        if !hasCodeSceneToken then "skipped - no CS_ACCESS_TOKEN"
        else
          val token = CodeScene.token
          assert(token.nonEmpty)
          "token retrieved successfully"

      test("devSettings - returns non-empty list"):
        if !hasCodeSceneToken then "skipped - no CS_ACCESS_TOKEN"
        else
          val settings = CodeScene.devSettings
          assert(settings.nonEmpty)
          settings.foreach { s =>
            assert(s.id > 0)
            assert(s.name.nonEmpty)
          }
          s"Found ${settings.size} developer settings"

      test("devSettingsRaw - returns valid JSON with developer_settings key"):
        if !hasCodeSceneToken then "skipped - no CS_ACCESS_TOKEN"
        else
          val raw = CodeScene.devSettingsRaw
          assert(raw.obj.contains("developer_settings"))
          val arr = raw.obj("developer_settings").arr
          assert(arr.nonEmpty)

      test("projects - returns valid JSON with projects key"):
        if !hasCodeSceneToken then "skipped - no CS_ACCESS_TOKEN"
        else
          val projects = CodeScene.projects
          assert(projects.obj.contains("projects"))
          val arr      = projects.obj("projects").arr
          assert(arr.nonEmpty)
          arr.foreach { p =>
            assert(p.obj.contains("id"))
            assert(p.obj.contains("name"))
          }
          s"Found ${arr.size} projects"

      test("teams - returns list for known dev setting"):
        if !hasCodeSceneToken then "skipped - no CS_ACCESS_TOKEN"
        else
          val settings = CodeScene.devSettings
          val firstId  = settings.head.id
          val teams    = CodeScene.teams(firstId)
          s"Found ${teams.size} teams for dev setting $firstId"

      test("developers - returns list for known dev setting"):
        if !hasCodeSceneToken then "skipped - no CS_ACCESS_TOKEN"
        else
          val settings   = CodeScene.devSettings
          val firstId    = settings.head.id
          val developers = CodeScene.developers(firstId)
          assert(developers.nonEmpty)
          developers.foreach { d =>
            assert(d.id > 0)
            assert(d.name.nonEmpty)
            assert(d.email.nonEmpty)
          }
          s"Found ${developers.size} developers for dev setting $firstId"

      test("project - returns valid data for first project"):
        if !hasCodeSceneToken then "skipped - no CS_ACCESS_TOKEN"
        else
          val projects = CodeScene.projects
          val firstId  = projects.obj("projects").arr.head.obj("id").num.toInt
          val project  = CodeScene.project(firstId)
          assert(project.obj.contains("name"))
          assert(project.obj.contains("id"))
          s"Project: ${project.obj("name").str}"

      test("latestAnalysis - returns data for first project"):
        if !hasCodeSceneToken then "skipped - no CS_ACCESS_TOKEN"
        else
          val projects = CodeScene.projects
          val firstId  = projects.obj("projects").arr.head.obj("id").num.toInt
          val analysis = CodeScene.latestAnalysis(firstId)
          assert(analysis.obj.nonEmpty)

    // -- PortIO integration tests --

    test("PortIO"):

      test("clientId - fails with clear message when env var missing"):
        if hasPortIOCredentials then
          val id = PortIO.clientId
          assert(id.nonEmpty)
        else
          val result = Try(PortIO.clientId)
          assert(result.isFailure)
          val msg    = result.failed.get.getMessage
          assert(msg.contains("PORT_CLIENT_ID"))

      test("secret - fails with clear message when env var missing"):
        if hasPortIOCredentials then
          val s = PortIO.secret
          assert(s.nonEmpty)
        else
          val result = Try(PortIO.secret)
          assert(result.isFailure)
          val msg    = result.failed.get.getMessage
          assert(msg.contains("PORT_CLIENT_SECRET"))

      test("accessToken cache - reuses token on second call"):
        if !hasPortIOCredentials then "skipped - no PORT_CLIENT_ID/PORT_CLIENT_SECRET"
        else
          // Reset cache
          PortIO.accessTokenCache = None
          val token1       = PortIO.accessToken
          assert(token1.nonEmpty)
          // Second call should use cache
          val cachedBefore = PortIO.accessTokenCache
          val token2       = PortIO.accessToken
          assert(token1 == token2)
          assert(PortIO.accessTokenCache == cachedBefore)
          "Token cache working correctly"

    // -- DeveloperExperience helper tests with live data --

    test("DeveloperExperience"):

      test("exportAll - writes expected directory structure"):
        if !hasCodeSceneToken then "skipped - no CS_ACCESS_TOKEN"
        else
          import DeveloperExperience.*
          val tmp = os.temp.dir()

          // Export developer settings
          val allSettings = CodeScene.devSettingsRaw
          writeJson(tmp / "developer-settings.json", allSettings)
          assert(os.exists(tmp / "developer-settings.json"))

          val settingsList = CodeScene.devSettings
          for (setting <- settingsList) {
            val slug       = slugify(setting.name)
            val settingDir = tmp / "developer-settings" / slug
            val teamsData  = CodeScene.teamsRaw(setting.id)
            writeJson(settingDir / "teams.json", teamsData)
            val devsData   = CodeScene.developersRaw(setting.id)
            writeJson(settingDir / "developers.json", devsData)
          }

          // Verify structure
          settingsList.foreach { setting =>
            val slug = slugify(setting.name)
            assert(os.exists(tmp / "developer-settings" / slug / "teams.json"))
            assert(os.exists(tmp / "developer-settings" / slug / "developers.json"))
          }

          // Export projects list
          val allProjects = CodeScene.projects
          writeJson(tmp / "projects.json", allProjects)
          assert(os.exists(tmp / "projects.json"))

          val projectList = allProjects.obj.get("projects").map(_.arr.toList).getOrElse(List.empty)
          s"Exported ${settingsList.size} settings, ${projectList.size} projects"

      test("exportProject - fetches and writes project data"):
        if !hasCodeSceneToken then "skipped - no CS_ACCESS_TOKEN"
        else
          import DeveloperExperience.*
          val tmp       = os.temp.dir()
          val projects  = CodeScene.projects
          val firstProj = projects.obj("projects").arr.head
          val projectId = firstProj.obj("id").num.toInt
          val name      = firstProj.obj("name").str
          val slug      = slugify(name)
          val dir       = tmp / "projects" / slug

          // Fetch and write project data (replicating what exportProjectData does)
          tryFetch("project", name)(writeJson(dir / "project.json", CodeScene.project(projectId)))
          tryFetch("configuration", name)(writeJson(dir / "configuration.json", CodeScene.projectConfig(projectId)))
          tryFetch("components", name)(writeJson(dir / "components.json", CodeScene.components(projectId)))

          assert(os.exists(dir / "project.json"))
          s"Exported project '$name' to $dir"

      test("developerList - developers can be fetched and formatted"):
        if !hasCodeSceneToken then "skipped - no CS_ACCESS_TOKEN"
        else
          val settings   = CodeScene.devSettings.filter(_.name == "All-of-Adtechnacity")
          assert(settings.nonEmpty)
          val developers = CodeScene.developers(settings.head.id)
          assert(developers.nonEmpty)
          developers.foreach { dev =>
            val formatted =
              s"${dev.name} | team=${dev.team_name} | email=${dev.email} | former=${dev.former_contributor}"
            assert(formatted.nonEmpty)
          }
          s"${developers.size} developers formatted successfully"

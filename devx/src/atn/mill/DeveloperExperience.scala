package atn.mill

import mill.api.{BuildCtx, DefaultTaskModule, Discover, ExternalModule, Result, Task}
import atn.mill.PortIO

import scala.util.Try

trait DeveloperExperience extends DefaultTaskModule {
  import DeveloperExperience.*

  def devSettings   = CodeScene.devSettings.filter(_.name == "All-of-Adtechnacity").head
  def upsertTeams() =
    Task.Command[Unit] {
      val teams  = CodeScene.teams(devSettings.id).map(t => PortIO.Entity(t.id.toString(), t.name))
      val upload = PortIO.upload_blueprint("code_scene_teams", true, teams)

      if (upload.statusCode < 300)
        Result.Success(())
      else
        Result.Failure(s"Failed to upsert Teams.\n${upload.text()}")
    }

  def developerList() =
    Task.Command[Unit] {
      val developers = CodeScene.developers(devSettings.id)
      developers.foreach { dev =>
        Task.log.info(s"${dev.name} | team=${dev.team_name} | email=${dev.email} | former=${dev.former_contributor}")
      }
      Task.log.info(s"Total: ${developers.size} developers")
    }

  def exportAll(outputDir: String = "codescene-export") =
    Task.Command[Unit] {
      val dest = os.Path(outputDir, BuildCtx.workspaceRoot)

      // Export developer settings
      val allSettings = CodeScene.devSettingsRaw
      writeJson(dest / "developer-settings.json", allSettings)

      val settingsList = CodeScene.devSettings
      for (setting <- settingsList) {
        val slug       = slugify(setting.name)
        val settingDir = dest / "developer-settings" / slug
        val teamsData  = CodeScene.teamsRaw(setting.id)
        writeJson(settingDir / "teams.json", teamsData)
        val devsData   = CodeScene.developersRaw(setting.id)
        writeJson(settingDir / "developers.json", devsData)
      }

      // Export all projects
      val allProjects = CodeScene.projects
      writeJson(dest / "projects.json", allProjects)

      val projectList = allProjects.obj.get("projects").map(_.arr.toList).getOrElse(List.empty)
      for (p <- projectList) {
        val projectId   = p.obj("id").num.toInt
        val projectName = p.obj("name").str
        exportProjectData(dest, projectId, projectName)
      }

      Task.log.info(s"Export complete: ${settingsList.size} developer settings, ${projectList.size} projects → $dest")
      Result.Success(())
    }

  def exportProject(projectId: Int, outputDir: String = "codescene-export") =
    Task.Command[Unit] {
      val dest    = os.Path(outputDir, BuildCtx.workspaceRoot)
      val project = CodeScene.project(projectId)
      val name    = project.obj("name").str
      exportProjectData(dest, projectId, name)
      Task.log.info(s"Export complete: project '$name' (id=$projectId) → $dest")
      Result.Success(())
    }

  def importProject(configFile: String) =
    Task.Command[Unit] {
      val path = os.Path(configFile, BuildCtx.workspaceRoot)
      if (!os.exists(path))
        Result.Failure(s"Config file not found: $path")
      else {
        val configJson = ujson.read(os.read(path))
        val result     = CodeScene.importProjectConfig(configJson)
        Task.log.info(s"Import result: ${ujson.write(result, indent = 2)}")
        Result.Success(())
      }
    }

  def importTeams(teamsFile: String, devSettingId: Int) =
    Task.Command[Unit] {
      val teamsPath = os.Path(teamsFile, BuildCtx.workspaceRoot)
      val devsPath  = teamsPath / os.up / "developers.json"

      if (!os.exists(teamsPath))
        Result.Failure(s"Teams file not found: $teamsPath")
      else if (!os.exists(devsPath))
        Result.Failure(s"Developers file not found: $devsPath")
      else {
        val teamsJson = ujson.read(os.read(teamsPath))
        val devsJson  = ujson.read(os.read(devsPath))

        val existingTeams = CodeScene.teams(devSettingId).map(_.name).toSet
        val newTeamNames  = teamsToCreate(teamsJson, existingTeams)
        newTeamNames.foreach(CodeScene.createTeam(devSettingId, _))
        Task.log.info(
          s"Created ${newTeamNames.size} new teams (${teamNames(teamsJson).size - newTeamNames.size} already existed)"
        )

        val teamsByName    = CodeScene.teams(devSettingId).map(t => t.name -> t.id).toMap
        val devAssignments = resolveDevAssignments(devsJson, teamsByName)
        devAssignments.foreach { case DevAssignment(devId, teamId, formerContributor) =>
          CodeScene.updateDeveloper(devSettingId, devId, teamId, formerContributor)
        }
        Task.log.info(s"Updated ${devAssignments.size} developer assignments")
        Result.Success(())
      }
    }

  private def exportProjectData(dest: os.Path, projectId: Int, projectName: String): Unit = {
    val slug       = slugify(projectName)
    val projectDir = dest / "projects" / slug

    tryFetch("project", projectName)(writeJson(projectDir / "project.json", CodeScene.project(projectId)))
    tryFetch("configuration", projectName)(
      writeJson(projectDir / "configuration.json", CodeScene.projectConfig(projectId))
    )
    tryFetch("components", projectName)(writeJson(projectDir / "components.json", CodeScene.components(projectId)))
    tryFetch("repositories", projectName)(writeJson(projectDir / "repositories.json", CodeScene.repositories(projectId)))

    val analysisDir = projectDir / "analysis"
    tryFetch("latest analysis", projectName)(writeJson(analysisDir / "summary.json", CodeScene.latestAnalysis(projectId)))
    tryFetch("file metrics", projectName)(writeJson(analysisDir / "files.json", CodeScene.fileMetrics(projectId)))
    tryFetch("component analysis", projectName)(
      writeJson(analysisDir / "components.json", CodeScene.componentAnalysis(projectId))
    )
    tryFetch("author statistics", projectName)(
      writeJson(analysisDir / "author-statistics.json", CodeScene.authorStats(projectId))
    )
    tryFetch("branch statistics", projectName)(
      writeJson(analysisDir / "branch-statistics.json", CodeScene.branchStats(projectId))
    )
    tryFetch("technical debt", projectName)(
      writeJson(analysisDir / "technical-debt.json", CodeScene.technicalDebt(projectId))
    )
    tryFetch("commit activity", projectName)(
      writeJson(analysisDir / "commit-activity.json", CodeScene.commitActivity(projectId))
    )
    tryFetch("skills inventory", projectName)(
      writeJson(analysisDir / "skills-inventory.json", CodeScene.skillsInventory(projectId))
    )
  }

}

object DeveloperExperience extends ExternalModule with DeveloperExperience {

  override def defaultTask(): String = "upsertTeams"

  lazy val millDiscover: Discover = Discover[this.type]

  /** Developer assignment resolved from import JSON against known team IDs. */
  case class DevAssignment(devId: Int, teamId: Int, formerContributor: Boolean)

  /** Extract team names from a teams JSON payload. */
  def teamNames(teamsJson: ujson.Value): List[String] =
    teamsJson.obj("teams").arr.toList.map(_.obj("name").str)

  /** Determine which team names need to be created (not already existing). */
  def teamsToCreate(teamsJson: ujson.Value, existingTeams: Set[String]): List[String] =
    teamNames(teamsJson).filterNot(existingTeams.contains)

  /** Resolve developer assignments by matching developer team names to known team IDs. */
  def resolveDevAssignments(devsJson: ujson.Value, teamsByName: Map[String, Int]): List[DevAssignment] =
    devsJson.obj("developers").arr.toList.flatMap { dev =>
      teamsByName.get(dev.obj("team_name").str).map { teamId =>
        DevAssignment(dev.obj("id").num.toInt, teamId, dev.obj("former_contributor").bool)
      }
    }

  def slugify(name: String): String =
    name.toLowerCase.replaceAll("[^a-z0-9]+", "-").stripPrefix("-").stripSuffix("-")

  def writeJson(path: os.Path, data: ujson.Value): Unit = {
    os.makeDir.all(path / os.up)
    os.write.over(path, ujson.write(data, indent = 2))
  }

  def tryFetch(resource: String, projectName: String)(block: => Unit): Unit =
    Try(block).recover { case e: Exception =>
      System.err.println(s"[warn] Failed to fetch $resource for '$projectName': ${e.getMessage}")
    }
}

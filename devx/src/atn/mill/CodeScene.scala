package atn.mill

import upickle.{default => json}

object CodeScene {

  /** Environment variable name for the CodeScene API access token. Override via config if needed. */
  var csAccessTokenEnvVar: String = "CS_ACCESS_TOKEN"

  trait Entry {
    def id: Int
    def name: String
    def ref: String
  }

  case class DevSettingsEntry(id: Int, name: String, ref: String) extends Entry

  object DevSettingsEntry {
    implicit val rw: json.ReadWriter[DevSettingsEntry] = json.macroRW
  }

  case class Developer(
    id: Int,
    name: String,
    team_name: String,
    email: String,
    emails: List[String],
    former_contributor: Boolean,
    ref: String
  ) extends Entry

  object Developer {
    implicit val rw: json.ReadWriter[Developer] = json.macroRW
  }

  val api = "https://api.codescene.io/v2"

  private val DevSettings    = "/developer-settings"
  private val Projects       = "/projects"
  private val AnalysesLatest = "/analyses/latest"
  private val JsonContent    = "Content-Type" -> "application/json"

  def token = Option(System.getenv(csAccessTokenEnvVar))
    .filter(_.nonEmpty)
    .getOrElse(throw new RuntimeException(s"Environment variable $csAccessTokenEnvVar is not set or empty"))

  def headers = Map("Accept" -> "application/json", "Authorization" -> s"Bearer $token")

  private def get(path: String): ujson.Value =
    ujson.read(requests.get(url = s"$api$path", headers = headers).text())

  private def post(path: String, body: ujson.Value): ujson.Value =
    ujson.read(
      requests
        .post(url = s"$api$path", headers = headers + JsonContent, data = ujson.write(body))
        .text()
    )

  private def put(path: String, body: ujson.Value): ujson.Value =
    ujson.read(
      requests
        .put(url = s"$api$path", headers = headers + JsonContent, data = ujson.write(body))
        .text()
    )

  private def devSettingPath(devSettingId: Int): String = s"$DevSettings/$devSettingId"
  private def projectPath(projectId: Int): String       = s"$Projects/$projectId"

  def devSettings: List[DevSettingsEntry] =
    json.read[List[DevSettingsEntry]](get(DevSettings).obj("developer_settings"))

  def devSettingsRaw: ujson.Value = get(DevSettings)

  def teams(devSettingId: Int) =
    json.read[List[DevSettingsEntry]](get(s"${devSettingPath(devSettingId)}/teams").obj("teams"))

  def teamsRaw(devSettingId: Int): ujson.Value =
    get(s"${devSettingPath(devSettingId)}/teams")

  def developers(devSettingId: Int) =
    json.read[List[Developer]](get(s"${devSettingPath(devSettingId)}/developers").obj("developers"))

  def developersRaw(devSettingId: Int): ujson.Value =
    get(s"${devSettingPath(devSettingId)}/developers")

  // Project discovery
  def projects: ujson.Value                = get(Projects)
  def project(projectId: Int): ujson.Value = get(projectPath(projectId))

  // Configuration export/import
  def projectConfig(projectId: Int): ujson.Value =
    get(s"${projectPath(projectId)}/export/configuration/json")

  def importProjectConfig(configJson: ujson.Value): ujson.Value =
    post(s"$Projects/new", configJson)

  // Project resources
  def components(projectId: Int): ujson.Value   = get(s"${projectPath(projectId)}/components")
  def repositories(projectId: Int): ujson.Value = get(s"${projectPath(projectId)}/repositories")

  // Latest analysis data
  private def analysisResource(projectId: Int, resource: String): ujson.Value =
    get(s"${projectPath(projectId)}$AnalysesLatest/$resource")

  def latestAnalysis(projectId: Int): ujson.Value    = get(s"${projectPath(projectId)}$AnalysesLatest")
  def fileMetrics(projectId: Int): ujson.Value       = analysisResource(projectId, "files")
  def componentAnalysis(projectId: Int): ujson.Value = analysisResource(projectId, "components")
  def authorStats(projectId: Int): ujson.Value       = analysisResource(projectId, "author-statistics")
  def branchStats(projectId: Int): ujson.Value       = analysisResource(projectId, "branch-statistics")
  def technicalDebt(projectId: Int): ujson.Value     = analysisResource(projectId, "technical-debt")
  def commitActivity(projectId: Int): ujson.Value    = analysisResource(projectId, "commit-activity")
  def skillsInventory(projectId: Int): ujson.Value   =
    analysisResource(projectId, "experience/languages")

  // Team management
  def createTeam(devSettingId: Int, name: String): ujson.Value =
    post(s"${devSettingPath(devSettingId)}/teams/new", ujson.Obj("name" -> name))

  def updateTeam(devSettingId: Int, teamId: Int, name: String): ujson.Value =
    put(s"${devSettingPath(devSettingId)}/teams/$teamId", ujson.Obj("name" -> name))

  // Developer management
  def updateDeveloper(devSettingId: Int, devId: Int, teamId: Int, formerContributor: Boolean): ujson.Value =
    put(
      s"${devSettingPath(devSettingId)}/developers/$devId",
      ujson.Obj("team_id" -> teamId, "former_contributor" -> formerContributor)
    )
}

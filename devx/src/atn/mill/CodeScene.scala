package atn.mill

import upickle.{default => json}

/**
 * Client for the [[https://codescene.io CodeScene]] REST API (v2).
 *
 * Requires an access token via the environment variable named by [[csAccessTokenEnvVar]] (defaults to
 * `CS_ACCESS_TOKEN`).
 *
 * @groupprio Models 10
 * @groupprio DeveloperSettings 20
 * @groupprio Projects 30
 * @groupprio Analysis 40
 * @groupprio TeamManagement 50
 * @groupprio DeveloperManagement 60
 */
object CodeScene {

  /** Environment variable name for the CodeScene API access token. */
  var csAccessTokenEnvVar: String = "CS_ACCESS_TOKEN"

  // --- Models ---

  /**
   * Common trait for CodeScene API entities.
   * @group Models
   */
  trait Entry {
    def id: Int
    def name: String
    def ref: String
  }

  /**
   * A developer-settings configuration entry.
   * @group Models
   */
  case class DevSettingsEntry(id: Int, name: String, ref: String) extends Entry

  object DevSettingsEntry {
    implicit val rw: json.ReadWriter[DevSettingsEntry] = json.macroRW
  }

  /**
   * A developer within a developer-settings configuration.
   * @group Models
   */
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

  // --- Internal plumbing ---

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

  // --- Developer Settings ---

  /**
   * List all developer-settings configurations.
   * @group DeveloperSettings
   */
  def devSettings: List[DevSettingsEntry] =
    json.read[List[DevSettingsEntry]](get(DevSettings).obj("developer_settings"))

  /**
   * List all developer-settings configurations (raw JSON).
   * @group DeveloperSettings
   */
  def devSettingsRaw: ujson.Value = get(DevSettings)

  /**
   * List teams within a developer-settings configuration.
   * @group DeveloperSettings
   */
  def teams(devSettingId: Int) =
    json.read[List[DevSettingsEntry]](get(s"${devSettingPath(devSettingId)}/teams").obj("teams"))

  /**
   * List teams within a developer-settings configuration (raw JSON).
   * @group DeveloperSettings
   */
  def teamsRaw(devSettingId: Int): ujson.Value =
    get(s"${devSettingPath(devSettingId)}/teams")

  /**
   * List developers within a developer-settings configuration.
   * @group DeveloperSettings
   */
  def developers(devSettingId: Int) =
    json.read[List[Developer]](get(s"${devSettingPath(devSettingId)}/developers").obj("developers"))

  /**
   * List developers within a developer-settings configuration (raw JSON).
   * @group DeveloperSettings
   */
  def developersRaw(devSettingId: Int): ujson.Value =
    get(s"${devSettingPath(devSettingId)}/developers")

  // --- Projects ---

  /**
   * List all projects.
   * @group Projects
   */
  def projects: ujson.Value = get(Projects)

  /**
   * Get a single project by ID.
   * @group Projects
   */
  def project(projectId: Int): ujson.Value = get(projectPath(projectId))

  /**
   * Export a project's configuration as JSON.
   * @group Projects
   */
  def projectConfig(projectId: Int): ujson.Value =
    get(s"${projectPath(projectId)}/export/configuration/json")

  /**
   * Import a project configuration, creating a new project.
   * @group Projects
   */
  def importProjectConfig(configJson: ujson.Value): ujson.Value =
    post(s"$Projects/new", configJson)

  /**
   * List components for a project.
   * @group Projects
   */
  def components(projectId: Int): ujson.Value = get(s"${projectPath(projectId)}/components")

  /**
   * List repositories for a project.
   * @group Projects
   */
  def repositories(projectId: Int): ujson.Value = get(s"${projectPath(projectId)}/repositories")

  // --- Analysis ---

  private def analysisResource(projectId: Int, resource: String): ujson.Value =
    get(s"${projectPath(projectId)}$AnalysesLatest/$resource")

  /**
   * Get the latest analysis summary for a project.
   * @group Analysis
   */
  def latestAnalysis(projectId: Int): ujson.Value = get(s"${projectPath(projectId)}$AnalysesLatest")

  /**
   * Get per-file metrics from the latest analysis.
   * @group Analysis
   */
  def fileMetrics(projectId: Int): ujson.Value = analysisResource(projectId, "files")

  /**
   * Get component-level analysis results.
   * @group Analysis
   */
  def componentAnalysis(projectId: Int): ujson.Value = analysisResource(projectId, "components")

  /**
   * Get author statistics from the latest analysis.
   * @group Analysis
   */
  def authorStats(projectId: Int): ujson.Value = analysisResource(projectId, "author-statistics")

  /**
   * Get branch statistics from the latest analysis.
   * @group Analysis
   */
  def branchStats(projectId: Int): ujson.Value = analysisResource(projectId, "branch-statistics")

  /**
   * Get technical debt data from the latest analysis.
   * @group Analysis
   */
  def technicalDebt(projectId: Int): ujson.Value = analysisResource(projectId, "technical-debt")

  /**
   * Get commit activity data from the latest analysis.
   * @group Analysis
   */
  def commitActivity(projectId: Int): ujson.Value = analysisResource(projectId, "commit-activity")

  /**
   * Get programming language skills inventory from the latest analysis.
   * @group Analysis
   */
  def skillsInventory(projectId: Int): ujson.Value =
    analysisResource(projectId, "experience/languages")

  // --- Team Management ---

  /**
   * Create a new team in a developer-settings configuration.
   * @group TeamManagement
   */
  def createTeam(devSettingId: Int, name: String): ujson.Value =
    post(s"${devSettingPath(devSettingId)}/teams/new", ujson.Obj("name" -> name))

  /**
   * Rename an existing team.
   * @group TeamManagement
   */
  def updateTeam(devSettingId: Int, teamId: Int, name: String): ujson.Value =
    put(s"${devSettingPath(devSettingId)}/teams/$teamId", ujson.Obj("name" -> name))

  // --- Developer Management ---

  /**
   * Update a developer's team assignment and contributor status.
   * @group DeveloperManagement
   */
  def updateDeveloper(devSettingId: Int, devId: Int, teamId: Int, formerContributor: Boolean): ujson.Value =
    put(
      s"${devSettingPath(devSettingId)}/developers/$devId",
      ujson.Obj("team_id" -> teamId, "former_contributor" -> formerContributor)
    )
}

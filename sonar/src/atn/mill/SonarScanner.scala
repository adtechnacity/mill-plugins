package atn.mill

import mill.*
import mill.api.{BuildCtx, DefaultTaskModule, Discover, Evaluator, ExternalModule, SelectMode, Task, TaskCtx => Ctx}
import mill.api.{PathRef, Result}
import cats.syntax.option.*
import ch.qos.logback.classic.{Level, LoggerContext}
import ch.qos.logback.classic.joran.JoranConfigurator
import org.slf4j.LoggerFactory
import org.sonarsource.scanner.lib.{
  AnalysisProperties,
  ScannerEngineBootstrapResult,
  ScannerEngineBootstrapper,
  ScannerProperties
}

import scala.jdk.CollectionConverters.*
import scala.util.Using
import scala.util.matching.Regex

trait SonarScanner extends DefaultTaskModule {

  /** SonarQube server URL. Must be overridden by consumers. */
  def sonarHostUrl: String

  /** SonarQube project key. Must be overridden by consumers. */
  def sonarProjectKey: String

  /** SonarQube project display name. Must be overridden by consumers. */
  def sonarProjectName: String

  /** SonarQube project base directory. Defaults to workspace root. */
  def sonarProjectBaseDir: os.Path = BuildCtx.workspaceRoot

  /** Current project version string as a Task. Must be overridden by consumers. */
  def projectVersion: Task[String]

  /** Environment variable name for the SonarQube authentication token. */
  def sonarTokenEnvVar: String = "SONAR_TOKEN"

  /** Environment variable name for the SonarQube log level. */
  def sonarLogLevelEnvVar: String = "SONAR_LOG_LEVEL"

  /** Mill task path for the coverage report (e.g. "scoverage.xmlReportAll"). Resolved dynamically via evaluator. */
  def coverageReportTaskPath: String = "scoverage.xmlReportAll"

  /**
   * Analyses the build on the SonarQube server: evaluates [[coverageReportTaskPath]], configures logging, bootstraps
   * the scanner with [[initProps]] and analyses every module of [[analysisProps]] (see [[SonarScanner.analyze]]).
   * Nothing is contacted before [[initProps]] succeeds, so a missing token fails here rather than at the server.
   */
  def sonar(evaluator: Evaluator) = Task.Command(exclusive = true)[Unit] {
    val version                 = projectVersion()
    val aggRep: Result[PathRef] =
      evaluator
        .evaluate(Seq(coverageReportTaskPath), SelectMode.Multi)
        .flatMap(_.values)
        .map(_.head.asInstanceOf[PathRef])
    Result
      .create(SonarScanner.configureLogging(Task.env.getOrElse(sonarLogLevelEnvVar, "WARN")))
      .flatMap(_ => initProps(aggRep, version))
      .flatMap { props =>
        ScannerEngineBootstrapper
          .create("sonar-mill", "0.1")
          .addBootstrapProperties(props.asJava)
          .bootstrap()
      }
      .flatMap(engine => SonarScanner.analyze(engine, analysisProps(evaluator)))
  }

  /**
   * The scanner's bootstrap properties: server, token, project, version, branch, coverage report and log level, read
   * from the build's environment (`ctx.env`) and the git repository. Fails when the [[sonarTokenEnvVar]] variable is
   * unset or empty.
   */
  def initProps(aggRep: Result[PathRef], projectVersion: String)(implicit ctx: Ctx): Result[Map[String, String]] =
    ctx.env.get(sonarTokenEnvVar).filter(_.nonEmpty) match {
      case None        =>
        Result.Failure(s"$sonarTokenEnvVar is not set: export the SonarQube token in it before running sonar")
      case Some(token) =>
        GitRepo.headBranch().map { head =>
          scannerProps(head, token, aggRep, projectVersion, ctx.env.getOrElse(sonarLogLevelEnvVar, "WARN"), ctx.dest)
        }
    }

  /**
   * The [[initProps]] map for a resolved `head` branch, `token` and `logLevel`; `workDir` is the scanner's working
   * directory. A branch other than `main` is analysed against `main` as its new-code reference.
   */
  private[mill] def scannerProps(
    head: String,
    token: String,
    aggRep: Result[PathRef],
    projectVersion: String,
    logLevel: String,
    workDir: os.Path
  ): Map[String, String] = {
    import ScannerProperties._
    import AnalysisProperties._
    val props = Map(
      HOST_URL                           -> sonarHostUrl,
      WORK_DIR                           -> workDir.toString(),
      SONAR_TOKEN                        -> token,
      PROJECT_KEY                        -> sonarProjectKey,
      PROJECT_NAME                       -> sonarProjectName,
      PROJECT_VERSION                    -> projectVersion,
      PROJECT_BASEDIR                    -> sonarProjectBaseDir.toString(),
      "sonar.branch.name"                -> head,
      "sonar.scala.coverage.reportPaths" -> aggRep.toOption.map(_.path./("scoverage.xml").toString()).getOrElse(""),
      "sonar.log.level"                  -> logLevel,
      "sonar.verbose"                    -> (logLevel == "DEBUG").toString
    )
    if (head == "main") props
    else props.updated("sonar.newCode.referenceBranch", "main")
  }

  /**
   * The analysis properties of every module with a `sources` task: each module is listed in `sonar.modules` with its
   * language and source directories; the sources of a nested module such as `app.test` are its parent's `sonar.tests`;
   * `scoverage` modules and Mill's own are skipped (see [[SonarScanner.moduleProps]]).
   */
  def analysisProps(evaluator: Evaluator): Map[String, String] =
    evaluator
      .resolveTasks(Seq("__.sources"), SelectMode.Multi)
      .toEither
      .fold(
        e => throw new InternalError(s"Could not resolve sources: $e"),
        _.foldLeft(Map.empty[String, String]) { (props, sources) =>
          val paths = evaluator.execute(Seq(sources)).values.get.flatMap(_.asInstanceOf[Seq[PathRef]]).map(_.path)
          SonarScanner.moduleProps(props, SonarScanner.moduleOf(sources), paths)
        }
      )

}

object SonarScanner extends ExternalModule with SonarScanner {
  override def defaultTask(): String = "sonar"

  // Defaults for ExternalModule companion (not typically used directly)
  def sonarHostUrl     = ""
  def sonarProjectKey  = ""
  def sonarProjectName = ""
  def projectVersion   = Task("")

  val millInternalModule: Regex = """^mill\.scalalib.*""".r
  val dependentModule: Regex    = """^([\w\.]+)\.(\w+)$""".r

  /** The module owning a named task, as `mill resolve` renders it: the task's path without its last segment. */
  private[mill] def moduleOf(task: Task.Named[?]): String = {
    val path = task.toString
    if (path == task.label) "" else path.stripSuffix(s".${task.label}")
  }

  /**
   * Folds one module's source directories into the analysis properties: a top-level module joins `sonar.modules` and
   * gets `<module>.sonar.language` and `<module>.sonar.sources`; a nested module's sources are appended to its parent's
   * `<parent>.sonar.tests`; nested `scoverage` modules and Mill's own `mill.scalalib` modules add nothing.
   */
  private[mill] def moduleProps(
    props: Map[String, String],
    module: String,
    sources: Seq[os.Path]
  ): Map[String, String] = {
    val srcs = sources.mkString(",")
    module match {
      case millInternalModule()            => props
      case dependentModule(_, "scoverage") => props
      case dependentModule(mod, _)         => props.updatedWith(s"$mod.sonar.tests")(_.fold(srcs)(s => s"$s,$srcs").some)
      case mod                             =>
        props
          .updatedWith("sonar.modules")(_.fold(mod)(p => s"$p,$mod").some)
          .updated(s"$mod.sonar.language", "scala")
          .updated(s"$mod.sonar.sources", srcs)
    }
  }

  /** The logback level a `SONAR_LOG_LEVEL` value names: DEBUG, INFO or ERROR in any case; anything else is WARN. */
  private[mill] def logLevel(name: String): Level = name.toUpperCase match {
    case "DEBUG" => Level.DEBUG
    case "INFO"  => Level.INFO
    case "ERROR" => Level.ERROR
    case _       => Level.WARN
  }

  /**
   * Configures logback from the bundled `sonar-logback.xml` (a console appender on the root logger) with the root at
   * [[logLevel]]`(level)`. The context is reset first, so a second run in the same Mill JVM does not add a second
   * appender.
   */
  private[mill] def configureLogging(level: String): Unit = {
    val context      = LoggerFactory.getILoggerFactory().asInstanceOf[LoggerContext]
    val configurator = new JoranConfigurator()
    configurator.setContext(context)
    context.reset()
    configurator.doConfigure(classOf[SonarScanner].getClassLoader().getResource("sonar-logback.xml"))
    context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).setLevel(logLevel(level))
  }

  /**
   * Analyses `props` (the [[analysisProps]] map) on a bootstrapped `engine`, closing the engine afterwards. A bootstrap
   * that did not succeed and an analysis the engine reports as failed are both failures pointing at the scanner log.
   */
  private[mill] def analyze(engine: ScannerEngineBootstrapResult, props: Map[String, String]): Result[Unit] =
    Using.resource(engine) { engine =>
      if (!engine.isSuccessful()) Result.Failure("Sonar bootstrap failed, read logs for details")
      else if (engine.getEngineFacade().analyze(props.asJava)) Result.Success(())
      else Result.Failure("Sonar analysis failed, read logs for details")
    }

  lazy val millDiscover: Discover = Discover[this.type]
}

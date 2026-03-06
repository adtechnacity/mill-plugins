package atn.mill

import mill.*
import mill.api.{BuildCtx, DefaultTaskModule, Discover, Evaluator, ExternalModule, SelectMode, Task, TaskCtx => Ctx}
import mill.api.{PathRef, Result}
import mill.api.daemon.Segments
import mill.util.Tasks
import cats.syntax.option.*
import ch.qos.logback.classic.{Level, LoggerContext}
import ch.qos.logback.core.util.{StatusPrinter, StatusPrinter2}
import org.sonarsource.scanner.lib.{AnalysisProperties, ScannerEngineBootstrapper, ScannerProperties}

import scala.jdk.CollectionConverters.*
import scala.util.matching.Regex
import org.slf4j.LoggerFactory
import ch.qos.logback.classic.joran.JoranConfigurator
import scala.io.Source

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

  def sonar(evaluator: Evaluator) = Task.Command(exclusive = true)[Unit] {
    val version = projectVersion()
    val aggRep: Result[PathRef] =
      evaluator.evaluate(Seq(coverageReportTaskPath), SelectMode.Multi)
        .flatMap(_.values)
        .map(_.head.asInstanceOf[PathRef])
    Result
      .create {
        val lc           = LoggerFactory.getILoggerFactory().asInstanceOf[LoggerContext]
        val configurator = new JoranConfigurator()
        configurator.setContext(lc)
        configurator.doConfigure(classOf[SonarScanner].getClassLoader().getResource("sonar-logback.xml"))

        val logLevel = System.getenv().getOrDefault(sonarLogLevelEnvVar, "WARN").toUpperCase match {
          case "DEBUG" => Level.DEBUG
          case "INFO"  => Level.INFO
          case "ERROR" => Level.ERROR
          case _       => Level.WARN
        }
        lc.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).setLevel(logLevel)
      }
      .flatMap(_ => initProps(aggRep, version))
      .flatMap { props =>
        ScannerEngineBootstrapper
          .create("sonar-mill", "0.1")
          .addBootstrapProperties(props.asJava)
          .bootstrap()
      }
      .flatMap { engine =>
        Result.create {
          if (!engine.isSuccessful())
            throw new IllegalStateException("Sonar bootstrap failed, read logs for details")
          engine
            .getEngineFacade()
            .analyze(analysisProps(evaluator).asJava)
          true
        }
      }
      .flatMap {
        case true  => Result.Success(())
        case false => Result.Failure("Sonar analysis failed, read logs for details")
      }
  }

  def initProps(aggRep: Result[PathRef], projectVersion: String)(implicit ctx: Ctx): Result[Map[String, String]] = {
    import ScannerProperties._
    import AnalysisProperties._
    GitRepo
      .headBranch()
      .map: head =>
        val logLevel = System.getenv().getOrDefault(sonarLogLevelEnvVar, "WARN")
        val props    = Map(
          HOST_URL                           -> sonarHostUrl,
          WORK_DIR                           -> ctx.dest.toString(),
          SONAR_TOKEN                        -> System.getenv(sonarTokenEnvVar),
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

  def analysisProps(evaluator: Evaluator) = {
    import SonarScanner.{millInternalModule, dependentModule}
    evaluator
      .resolveTasks(Seq("__.sources"), SelectMode.Multi)
      .toEither
      .fold(
        e => throw new InternalError(s"Could not resolve sources: $e"),
        _.foldLeft(Map.empty[String, String]) { (props, sources: Task.Named[?]) =>
          val module = Segments()
          val srcs   = evaluator
            .execute(Seq(sources))
            .executionResults
            .results
            .map(_.get.value.asInstanceOf[List[PathRef]])
            .flatten
            .map(_.path)
            .mkString(",")
          module.render match {
            case millInternalModule()              => props
            case dependentModule(mod, "scoverage") => props // Skip scoverage modules
            case dependentModule(mod, dep)         =>
              props.updatedWith(s"$mod.sonar.tests")(_.fold(srcs)(s => s"$s,$srcs").some)
            case mod                               =>
              props
                .updatedWith("sonar.modules")(_.fold(mod)(p => s"$p,$mod").some)
                .updated(s"$mod.sonar.language", "scala")
                .updated(s"$mod.sonar.sources", srcs)
          }
        }
      )
  }

}

object SonarScanner extends ExternalModule with SonarScanner {
  override def defaultTask(): String = "sonar"

  // Defaults for ExternalModule companion (not typically used directly)
  def sonarHostUrl              = ""
  def sonarProjectKey           = ""
  def sonarProjectName          = ""
  def projectVersion            = Task("")

  val millInternalModule: Regex = """^mill\.scalalib.*""".r
  val dependentModule: Regex    = """^([\w\.]+)\.(\w+)$""".r

  lazy val millDiscover: Discover = Discover[this.type]
}

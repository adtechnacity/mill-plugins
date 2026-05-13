package atn.mill

import com.goyeau.mill.scalafix.{CoursierUtils, ScalafixModule => UpstreamScalafixModule}
import coursier.Repository
import mill.*
import mill.api.{BuildCtx, Logger, Result, Task}
import mill.scalalib.*
import scalafix.interfaces.Scalafix
import scalafix.interfaces.ScalafixError.*

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

trait ScalafixSupport extends ScalaModule:

  /**
   * External Scalafix rule dependencies (published artifacts). Override to add community rules such as
   * `typelevel-scalafix` or `xuwei-k/scalafix-rules`.
   */
  def scalafixMvnDeps: T[Seq[Dep]] = Task(Seq.empty[Dep])

  /**
   * Local Mill modules whose JAR and runtime classpath are added to Scalafix's tool classpath. Use this to develop
   * scalafix rules in-repo without `publishLocal`. The modules must be Scala 2.13 (scalafix loads rules via a 2.13
   * classloader) and depend on `scalafix-core` cross-published `for3Use2_13`.
   */
  def scalafixToolModules: Seq[ScalaModule] = Seq.empty

  /**
   * Resolved tool classpath URLs derived from `scalafixToolModules`. Includes each module's own JAR plus its full
   * runtime classpath so transitive dependencies of in-repo rules are loadable too.
   */
  def scalafixToolClasspath: T[Seq[PathRef]] = Task {
    Task.traverse(scalafixToolModules)(m => Task.Anon(Seq(m.jar()) ++ m.runClasspath()))().flatten
  }

  def scalafix() = Task.Command[Unit] {
    ScalafixSupport.runScalafix(
      log = Task.log,
      repositories = repositoriesTask(),
      sources = UpstreamScalafixModule.filesToFix(allSources()).map(_.path),
      classpath = compileClasspath().map(_.path) ++ Seq(compiledClassesAndSemanticDbFiles().path),
      scalaVersion = scalaVersion(),
      scalacOptions = scalacOptions(),
      scalafixMvnDeps = scalafixMvnDeps(),
      scalafixToolJars = scalafixToolClasspath().map(_.path),
      scalafixConfig =
        Option.when(os.exists(BuildCtx.workspaceRoot / ".scalafix.conf"))(BuildCtx.workspaceRoot / ".scalafix.conf"),
      args = Seq.empty,
      wd = BuildCtx.workspaceRoot
    )
  }

  def scalafixCheck() = Task.Command[Unit] {
    ScalafixSupport.runScalafix(
      log = Task.log,
      repositories = repositoriesTask(),
      sources = UpstreamScalafixModule.filesToFix(allSources()).map(_.path),
      classpath = compileClasspath().map(_.path) ++ Seq(compiledClassesAndSemanticDbFiles().path),
      scalaVersion = scalaVersion(),
      scalacOptions = scalacOptions(),
      scalafixMvnDeps = scalafixMvnDeps(),
      scalafixToolJars = scalafixToolClasspath().map(_.path),
      scalafixConfig =
        Option.when(os.exists(BuildCtx.workspaceRoot / ".scalafix.conf"))(BuildCtx.workspaceRoot / ".scalafix.conf"),
      args = Seq("--check"),
      wd = BuildCtx.workspaceRoot
    )
  }

object ScalafixSupport:

  /**
   * Drives `scalafix-interfaces` directly so that both published rule deps (`scalafixMvnDeps`) and locally-compiled
   * rule jars (`scalafixToolJars`) can populate Scalafix's tool classpath. We bypass `goyeau.ScalafixModule.fixAction`
   * because its underlying `ScalafixCache` hardcodes `withToolClasspath(Seq.empty.asJava, …)` — there's no way to
   * forward local URLs through the published API.
   */
  def runScalafix(
    log: Logger,
    repositories: Seq[Repository],
    sources: Seq[os.Path],
    classpath: Seq[os.Path],
    scalaVersion: String,
    scalacOptions: Seq[String],
    scalafixMvnDeps: Seq[Dep],
    scalafixToolJars: Seq[os.Path],
    scalafixConfig: Option[os.Path],
    args: Seq[String],
    wd: os.Path
  ): Result[Unit] =
    if sources.isEmpty then Result.Success(())
    else
      val repos    = repositories.map(CoursierUtils.toApiRepository).asJava
      val deps     = scalafixMvnDeps.map(CoursierUtils.toCoordinates).asJava
      val toolUrls = scalafixToolJars.map(_.toNIO.toUri.toURL).asJava

      val scalafix  = Scalafix.fetchAndClassloadInstance(scalaVersion, repos)
      val arguments = scalafix
        .newArguments()
        .withParsedArguments(args.asJava)
        .withWorkingDirectory(wd.toNIO)
        .withConfig(scalafixConfig.map(_.toNIO).toJava)
        .withClasspath(classpath.map(_.toNIO).asJava)
        .withScalaVersion(scalaVersion)
        .withScalacOptions(scalacOptions.asJava)
        .withPaths(sources.map(_.toNIO).asJava)
        .withToolClasspath(toolUrls, deps, repos)

      log.info(s"Rewriting and linting ${sources.size} Scala sources against ${arguments.rulesThatWillRun.size} rules")
      val errors = arguments.run()
      if errors.isEmpty then Result.Success(())
      else
        val messages = errors.map {
          case ParseError             => "A source file failed to be parsed"
          case CommandLineError       =>
            arguments.validate().toScala.fold("A command-line argument was parsed incorrectly")(_.getMessage)
          case MissingSemanticdbError =>
            "A semantic rewrite was run on a source file that has no associated META-INF/semanticdb/.../*.semanticdb"
          case StaleSemanticdbError   =>
            """The source file contents on disk have changed since the last compilation with the SemanticDB compiler
              |plugin. To resolve this error re-compile the project and re-run Scalafix""".stripMargin
          case TestError              =>
            "A Scalafix test error was reported. Run `scalafix` without `--check` or `--diff` to fix the error"
          case LinterError            => "A Scalafix linter error was reported"
          case NoFilesError           => "No files were provided to Scalafix so nothing happened"
          case NoRulesError           => "No Scalafix rules were found. Make sure a `rules` set is defined in .scalafix.conf"
          case _                      => "Something unexpected happened running Scalafix"
        }
        Result.Failure(messages.mkString("\n"))

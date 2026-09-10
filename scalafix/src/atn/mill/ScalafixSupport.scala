package atn.mill

import com.goyeau.mill.scalafix.{CoursierUtils, ScalafixModule => UpstreamScalafixModule}
import coursier.Repository
import mill.*
import mill.api.{BuildCtx, Logger, Result, Task}
import mill.scalalib.*
import scalafix.interfaces.ScalafixError.*
import scalafix.interfaces.{Scalafix, ScalafixArguments, ScalafixError}

import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/**
 * Drop-in Scalafix support for a `ScalaModule` that adds two affordances on top of
 * [[com.goyeau.mill.scalafix.ScalafixModule goyeau's ScalafixModule]]:
 *   - [[scalafixMvnDeps]] for published rule artifacts; and
 *   - [[scalafixToolModules]] for in-repo rule modules (no `publishLocal` round trip).
 *
 * Exposes the standard `scalafix` and `scalafixCheck` commands.
 */
trait ScalafixSupport extends ScalaModule:

  /**
   * External Scalafix rule dependencies (published artifacts). Override to add community rules such as
   * `typelevel-scalafix` or `xuwei-k/scalafix-rules`.
   */
  def scalafixMvnDeps: T[Seq[Dep]] = Task(Seq.empty[Dep])

  /**
   * Local Mill modules whose compiled JAR and full runtime classpath are appended to Scalafix's tool classpath. Use
   * this to author and iterate on Scalafix rules directly inside the same repo, without going through `publishLocal`.
   *
   * The referenced modules must be Scala 2.13 because Scalafix loads rules through a 2.13 classloader regardless of the
   * target module's Scala version; rule modules typically depend on `ch.epfl.scala::scalafix-core:<version>`.
   */
  def scalafixToolModules: Seq[ScalaModule] = Seq.empty

  /**
   * Resolved tool classpath entries derived from [[scalafixToolModules]]. Each module contributes its own JAR plus its
   * full runtime classpath so that transitive dependencies of in-repo rules are loadable from the rule classloader.
   */
  def scalafixToolClasspath: T[Seq[PathRef]] = Task {
    Task.traverse(scalafixToolModules)(m => Task.Anon(Seq(m.jar()) ++ m.runClasspath()))().flatten
  }

  /** Rewrite sources in place by applying the configured Scalafix rules. */
  def scalafix() = Task.Command[Unit] {
    scalafix0(Seq.empty)()
  }

  /** Verify sources are already Scalafix-clean (no rewrites needed). Equivalent to `scalafix --check`. */
  def scalafixCheck() = Task.Command[Unit] {
    scalafix0(Seq("--check"))()
  }

  /** The Scalafix run shared by [[scalafix]] and [[scalafixCheck]], parameterised by the extra CLI arguments. */
  private def scalafix0(args: Seq[String]) = Task.Anon {
    ScalafixSupport.runScalafix(
      log = Task.log,
      repositories = repositoriesTask(),
      sources = UpstreamScalafixModule.filesToFix(allSources()).map(_.path),
      classpath = compileClasspath().map(_.path) ++ Seq(compiledClassesAndSemanticDbFiles().path),
      scalaVersion = scalaVersion(),
      scalacOptions = scalacOptions(),
      scalafixMvnDeps = scalafixMvnDeps(),
      scalafixToolClasspath = scalafixToolClasspath().map(_.path),
      scalafixConfig = ScalafixSupport.workspaceScalafixConfig,
      args = args,
      wd = BuildCtx.workspaceRoot
    )
  }

object ScalafixSupport:

  /** Path to `.scalafix.conf` at the workspace root, when present. */
  private def workspaceScalafixConfig: Option[os.Path] = scalafixConfigIn(BuildCtx.workspaceRoot)

  /** `root/.scalafix.conf` when that file exists. */
  private[mill] def scalafixConfigIn(root: os.Path): Option[os.Path] =
    Option.when(os.exists(root / ".scalafix.conf"))(root / ".scalafix.conf")

  /**
   * The inputs that determine the Scalafix tool classloader, and so the key of [[cachedArguments]]: Scala version,
   * repositories, rule dependencies and local tool classpath. Value-equal across modules that share a rule set.
   */
  private[mill] type ToolClasspathKey = (String, Seq[Repository], Seq[Dep], Seq[os.Path])

  /**
   * Caches the Scalafix tool classloader (scalafix-cli + rule classpath) across `runScalafix` invocations within a
   * single Mill JVM, keyed by the inputs that determine it.
   *
   * `Scalafix.fetchAndClassloadInstance` and `ScalafixArguments.withToolClasspath` each build a URLClassLoader that
   * loads hundreds of scalafix/scalameta classes. Building one per module — e.g. `./mill __.scalafixCheck` over a large
   * multi-module build — accumulates classloaders that are never released, exhausting the JVM's compressed class space
   * (`OutOfMemoryError: Compressed class space`). The tool classloader depends only on the Scala version, repositories,
   * rule deps, and tool classpath — all identical across modules that share a rule set — so a single instance is built
   * once and reused.
   *
   * Mirrors `com.goyeau.mill.scalafix.ScalafixCache`, but threads through local tool-classpath URLs (which the upstream
   * cache hardcodes empty), keeping in-repo `scalafixToolModules` loadable. Entries are held strongly rather than via
   * `SoftReference`: soft references are reclaimed under heap pressure, not class-space pressure, so they would not
   * prevent the metaspace exhaustion this cache exists to avoid. The key is value-equal, so there is one entry per
   * distinct rule set, retained for the (short-lived) Mill JVM.
   */
  private val toolClasspathCache = new ConcurrentHashMap[ToolClasspathKey, ScalafixArguments]()

  /** The arguments cached for `key`; `fetch` builds them on the first request for that key only. */
  private[mill] def cachedArguments(key: ToolClasspathKey)(fetch: => ScalafixArguments): ScalafixArguments =
    toolClasspathCache.computeIfAbsent(key, _ => fetch)

  private def baseArguments(
    scalaVersion: String,
    repositories: Seq[Repository],
    scalafixMvnDeps: Seq[Dep],
    scalafixToolClasspath: Seq[os.Path]
  ): ScalafixArguments =
    cachedArguments((scalaVersion, repositories, scalafixMvnDeps, scalafixToolClasspath)) {
      val repos    = repositories.map(CoursierUtils.toApiRepository).asJava
      val deps     = scalafixMvnDeps.map(CoursierUtils.toCoordinates).asJava
      val toolUrls = scalafixToolClasspath.map(_.toNIO.toUri.toURL).asJava
      Scalafix
        .fetchAndClassloadInstance(scalaVersion, repos)
        .newArguments()
        .withToolClasspath(toolUrls, deps, repos)
    }

  /**
   * Internal Scalafix driver. Calls `scalafix-interfaces` directly so that locally-compiled rule JARs (via
   * [[ScalafixSupport.scalafixToolClasspath]]) can be added to the tool classpath alongside published rule deps
   * ([[ScalafixSupport.scalafixMvnDeps]]).
   *
   * Bypasses `com.goyeau.mill.scalafix.ScalafixModule.fixAction` because the underlying `ScalafixCache` hardcodes
   * `withToolClasspath(Seq.empty.asJava, deps, repos)` — there is no published API hook to forward local URLs.
   */
  private[mill] def runScalafix(
    log: Logger,
    repositories: Seq[Repository],
    sources: Seq[os.Path],
    classpath: Seq[os.Path],
    scalaVersion: String,
    scalacOptions: Seq[String],
    scalafixMvnDeps: Seq[Dep],
    scalafixToolClasspath: Seq[os.Path],
    scalafixConfig: Option[os.Path],
    args: Seq[String],
    wd: os.Path
  ): Result[Unit] =
    run(
      log,
      baseArguments(scalaVersion, repositories, scalafixMvnDeps, scalafixToolClasspath),
      ModuleInputs(
        scalaVersion = scalaVersion,
        scalacOptions = scalacOptions,
        sources = sources,
        classpath = classpath,
        config = scalafixConfig,
        args = args,
        workingDirectory = wd
      )
    )

  /** The per-module inputs of one Scalafix run: everything but the cached tool classloader. */
  final private[mill] case class ModuleInputs(
    scalaVersion: String,
    scalacOptions: Seq[String],
    sources: Seq[os.Path],
    classpath: Seq[os.Path],
    config: Option[os.Path],
    args: Seq[String],
    workingDirectory: os.Path
  )

  /**
   * Runs Scalafix over `inputs` on top of the tool-classloader arguments `base`, which are only forced when there is
   * something to run: a module without Scala sources succeeds without fetching or loading scalafix-cli at all. Every
   * error Scalafix reports becomes one line of the failure message.
   */
  private[mill] def run(log: Logger, base: => ScalafixArguments, inputs: ModuleInputs): Result[Unit] =
    if inputs.sources.isEmpty then Result.Success(())
    else
      // The tool classloader (scalafix-cli + rules) is cached and reused; only the cheap per-module arguments below are
      // rebuilt each call. ScalafixArguments is an immutable builder, so deriving per-module args off the shared cached
      // instance is safe under Mill's parallel module evaluation.
      val arguments = moduleArguments(base, inputs)
      log.info(
        s"Rewriting and linting ${inputs.sources.size} Scala sources against ${arguments.rulesThatWillRun.size} rules"
      )
      val errors    = arguments.run()
      if errors.isEmpty then Result.Success(())
      else Result.Failure(errors.map(describeError(_, arguments)).mkString("\n"))

  /** `base` with the per-module `inputs` applied: CLI arguments, working directory, config, classpath and sources. */
  private[mill] def moduleArguments(base: ScalafixArguments, inputs: ModuleInputs): ScalafixArguments =
    base
      .withParsedArguments(inputs.args.asJava)
      .withWorkingDirectory(inputs.workingDirectory.toNIO)
      .withConfig(inputs.config.map(_.toNIO).toJava)
      .withClasspath(inputs.classpath.map(_.toNIO).asJava)
      .withScalaVersion(inputs.scalaVersion)
      .withScalacOptions(inputs.scalacOptions.asJava)
      .withPaths(inputs.sources.map(_.toNIO).asJava)

  /** Human-readable description for a [[ScalafixError]] returned from `Scalafix.run`. */
  private[mill] def describeError(error: ScalafixError, arguments: ScalafixArguments): String =
    error match
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

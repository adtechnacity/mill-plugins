package atn.mill

import cats.data.NonEmptyList
import cats.effect.{Deferred, IO, Resource}
import stryker4s.config.Config
import stryker4s.config.source.ConfigSource
import stryker4s.log.Logger
import stryker4s.model.CompilerErrMsg
import stryker4s.mutants.tree.InstrumenterOptions
import stryker4s.run.{Stryker4sRunner, TestRunner}
import stryker4s.testrunner.api.*

import scala.concurrent.duration.FiniteDuration

/**
 * Mill-native Stryker4s runner.
 *
 * Uses `InstrumenterOptions.testRunner`, so the instrumented code reports per-test '''coverage''' through
 * `stryker4s.coverage.coverMutant` and activates mutations in-process via `stryker4s.activeMutation` — both provided by
 * the forked `stryker4s-sbt-testrunner` server [[MillProcessTestRunner]] talks to. Each mutant therefore runs only the
 * tests that cover it, inside a warm long-lived JVM, instead of the whole suite in a fresh fork.
 *
 * @param testClasspath
 *   resolved test classpath (compiled production + test classes + all deps)
 * @param frameworkName
 *   fully-qualified test framework class name
 * @param testClasses
 *   fully-qualified test class names
 * @param concurrency
 *   number of parallel test runners
 */
class Stryker4sMillRunner(
  testClasspath: Seq[os.Path],
  frameworkName: String,
  testClasses: Seq[String],
  concurrency: Int,
  scalaVersion: String,
  moduleSourceDirs: Seq[os.Path],
  scalacOptions: Seq[String] = Seq.empty,
  testRunnerJavaOpts: Seq[String] = Seq.empty
)(using logger: Logger)
    extends Stryker4sRunner:

  override def instrumenterOptions(using config: Config): InstrumenterOptions =
    InstrumenterOptions.testRunner

  override def resolveTestRunners(tmpDir: fs2.io.file.Path)(using
    config: Config
  ): Either[NonEmptyList[CompilerErrMsg], NonEmptyList[Resource[IO, TestRunner]]] =
    val sourceDir = os.Path(tmpDir.toNioPath)
    val classDir  = sourceDir / "classes"
    os.makeDir.all(classDir)

    // The forked server provides stryker4s.activeMutation + stryker4s.coverage for the instrumented code, and the
    // socket protocol handler. Resolved transitively so the scalapb runtime rides along.
    val testRunnerCp = Stryker4sMillRunner.resolveTestRunnerArtifact(scalaVersion)

    // Only compile source files from the module's source directories (not entire workspace).
    // Stryker4s copies the whole workspace to tmpDir, but we only need the mutated module's files.
    val workspaceDir       = config.baseDir.toNioPath
    val relativeSourceDirs = moduleSourceDirs.map(_.relativeTo(os.Path(workspaceDir)))
    val scalaFiles         = relativeSourceDirs.flatMap { relDir =>
      val dirInTmp = sourceDir / relDir
      if os.exists(dirInTmp) then os.walk(dirInTmp).filter(_.ext == "scala")
      else Seq.empty
    }

    if scalaFiles.nonEmpty then
      logger.info(s"Compiling ${scalaFiles.size} instrumented source file(s)...")

      // Resolve Scala 3 compiler classpath via coursier
      @annotation.nowarn("msg=deprecated")
      val compilerCp = coursier
        .Fetch()
        .addDependencies(
          coursier.Dependency(
            coursier.Module(coursier.Organization("org.scala-lang"), coursier.ModuleName("scala3-compiler_3")),
            scalaVersion
          )
        )
        .run()
        .toSeq
        .map(_.getAbsolutePath)

      // Instrumented sources reference stryker4s.coverage.coverMutant / stryker4s.activeMutation — the testrunner
      // artifact must be on the compile classpath too.
      val compileCp        =
        (testRunnerCp.map(_.toString) ++ testClasspath.map(_.toString)).mkString(java.io.File.pathSeparator)
      val compilerAndLibCp = (compilerCp ++ testRunnerCp.map(_.toString) ++ testClasspath.map(_.toString))
        .mkString(java.io.File.pathSeparator)

      // Filter scalac options: keep language/source settings, drop fatal warnings and plugin paths
      val filteredScalacOpts = scalacOptions.filterNot { opt =>
        opt == "-Xfatal-warnings" ||
        opt == "-Yexplicit-nulls" ||
        opt.startsWith("-Xplugin") ||
        opt.startsWith("-P:") ||
        opt.contains("semanticdb") ||
        opt.contains("unused")
      }

      val javaBin           = os.Path(sys.props("java.home")) / "bin" / "java"
      val args: Seq[String] = Seq(
        javaBin.toString,
        "-cp",
        compilerAndLibCp,
        "dotty.tools.dotc.Main",
        "-d",
        classDir.toString,
        "-classpath",
        compileCp
      ) ++ filteredScalacOpts ++ scalaFiles.map(_.toString)
      val scalacResult      = os.proc(args).call(check = false, stdout = os.Inherit, stderr = os.Inherit)

      if scalacResult.exitCode != 0 then
        logger.warn(s"Compilation of instrumented sources failed (exit code ${scalacResult.exitCode})")
        return Left(NonEmptyList.one(CompilerErrMsg("Compilation failed", sourceDir.toString, Integer.valueOf(0))))

      logger.info(s"Compiled instrumented sources to $classDir")

    // Mutated classes shadow the originals; the testrunner artifact precedes the test classpath.
    val runnerClasspath = classDir +: (testRunnerCp ++ testClasspath)
    val testGroups      = Stryker4sMillRunner.buildTestGroups(testClasspath, frameworkName, testClasses)

    // Shared across runners: set once from the initial run's duration, read by every timeoutRunner.
    val sharedTimeout = Deferred.unsafe[IO, FiniteDuration]

    val runners = (1 to concurrency).map { _ =>
      val process = MillProcessTestRunner.newProcess(
        classpath = runnerClasspath,
        javaOpts = testRunnerJavaOpts,
        testGroups = testGroups,
        workingDir = sourceDir
      )
      TestRunner.retryRunner(TestRunner.timeoutRunner(sharedTimeout, process))
    }.toList
    Right(NonEmptyList.fromListUnsafe(runners))

  override def extraConfigSources: List[ConfigSource[IO]] = List.empty

object Stryker4sMillRunner:

  /** The stryker4s version this plugin is compiled against (used to resolve the matching testrunner artifact). */
  private def stryker4sVersion: String =
    Option(classOf[Stryker4sRunner].getPackage.getImplementationVersion).getOrElse("0.20.3")

  /** Resolve `stryker4s-sbt-testrunner` (plain Scala 3, sbt-free) with its transitive deps via coursier. */
  private def resolveTestRunnerArtifact(scalaVersion: String): Seq[os.Path] =
    val scalaBinary = if scalaVersion.startsWith("3") then "3" else scalaVersion.split('.').take(2).mkString(".")
    @annotation.nowarn("msg=deprecated")
    val files       = coursier
      .Fetch()
      .addDependencies(
        coursier.Dependency(
          coursier.Module(
            coursier.Organization("io.stryker-mutator"),
            coursier.ModuleName(s"stryker4s-sbt-testrunner_$scalaBinary")
          ),
          stryker4sVersion
        )
      )
      .run()
      .toSeq
    files
      .map(f => os.Path(f.getAbsolutePath))
      // The module's own classpath must provide the Scala stdlib — the testrunner's transitive stdlib (built against
      // a different Scala 3 minor) would otherwise shadow it and break the instrumented compile (Predef unreadable).
      .filterNot(p => p.last.startsWith("scala3-library") || p.last.startsWith("scala-library"))

  /**
   * Build the [[TestProcessContext]] test groups the server runs: one group for the module's framework, one
   * [[TaskDefinition]] per discovered test class. The framework is loaded in a throwaway classloader over the test
   * classpath purely to read its fingerprint (same fingerprint for every class, as test discovery already ran in Mill).
   */
  private def buildTestGroups(
    testClasspath: Seq[os.Path],
    frameworkName: String,
    testClasses: Seq[String]
  ): Seq[TestGroup] =
    val urls = testClasspath.map(_.toNIO.toUri.toURL).toArray
    val cl   = new java.net.URLClassLoader(urls, getClass.getClassLoader)
    try
      val framework   = Class
        .forName(frameworkName, true, cl)
        .getDeclaredConstructor()
        .newInstance()
        .asInstanceOf[sbt.testing.Framework]
      val fingerprint = toApiFingerprint(framework.fingerprints().head)
      val taskDefs    = testClasses.map { className =>
        TaskDefinition(className, fingerprint, explicitlySpecified = false, selectors = Seq(SuiteSelector()))
      }
      Seq(TestGroup(frameworkName, taskDefs, Some(RunnerOptions(Seq.empty, Seq.empty))))
    finally cl.close()

  private def toApiFingerprint(fp: sbt.testing.Fingerprint): Fingerprint = fp match
    case a: sbt.testing.AnnotatedFingerprint => AnnotatedFingerprint(a.isModule(), a.annotationName())
    case s: sbt.testing.SubclassFingerprint  =>
      SubclassFingerprint(s.isModule(), s.superclassName(), s.requireNoArgConstructor())

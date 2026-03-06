package atn.mill

import cats.data.NonEmptyList
import cats.effect.{IO, Resource}
import stryker4s.config.Config
import stryker4s.config.source.ConfigSource
import stryker4s.log.Logger
import stryker4s.model.CompilerErrMsg
import stryker4s.mutants.applymutants.ActiveMutationContext
import stryker4s.mutants.tree.InstrumenterOptions
import stryker4s.run.{Stryker4sRunner, TestRunner}

/**
 * Mill-native Stryker4s runner.
 *
 * Uses `InstrumenterOptions.sysContext` so mutations are activated via `-DACTIVE_MUTATION=<id>` system property in
 * forked test JVMs. This eliminates the need to spawn full Mill subprocesses per mutation.
 *
 * @param testClasspath
 *   resolved test classpath (compiled production + test classes + all deps)
 * @param testRunnerClassDir
 *   directory containing compiled StrykerTestRunnerMain class
 * @param frameworkName
 *   fully-qualified test framework class name
 * @param testClasses
 *   fully-qualified test class names
 * @param concurrency
 *   number of parallel test runners
 * @param testTimeout
 *   max duration per test run in milliseconds
 */
class Stryker4sMillRunner(
  testClasspath: Seq[os.Path],
  testRunnerClassDir: os.Path,
  frameworkName: String,
  testClasses: Seq[String],
  concurrency: Int,
  testTimeout: Long,
  scalaVersion: String,
  moduleSourceDirs: Seq[os.Path],
  scalacOptions: Seq[String] = Seq.empty
)(using logger: Logger)
    extends Stryker4sRunner:

  override def instrumenterOptions(using config: Config): InstrumenterOptions =
    InstrumenterOptions.sysContext(ActiveMutationContext.sysProps)

  override def resolveTestRunners(tmpDir: fs2.io.file.Path)(using
    config: Config
  ): Either[NonEmptyList[CompilerErrMsg], NonEmptyList[Resource[IO, TestRunner]]] =
    val sourceDir = os.Path(tmpDir.toNioPath)
    val classDir  = sourceDir / "classes"
    os.makeDir.all(classDir)

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

      val compileCp        = testClasspath.map(_.toString).mkString(java.io.File.pathSeparator)
      val compilerAndLibCp = (compilerCp ++ testClasspath.map(_.toString)).mkString(java.io.File.pathSeparator)

      // Filter scalac options: keep language/source settings, drop fatal warnings and plugin paths
      val filteredScalacOpts = scalacOptions.filterNot { opt =>
        opt == "-Xfatal-warnings" ||
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

    val runners = (1 to concurrency).map { _ =>
      Resource.pure[IO, TestRunner](
        new MillTestRunner(
          testClasspath = testClasspath,
          testRunnerCp = testRunnerClassDir,
          frameworkName = frameworkName,
          testClasses = testClasses,
          testTimeout = testTimeout,
          mutatedClassDir = Some(classDir)
        )
      )
    }.toList
    Right(NonEmptyList.fromListUnsafe(runners))

  override def extraConfigSources: List[ConfigSource[IO]] = List.empty

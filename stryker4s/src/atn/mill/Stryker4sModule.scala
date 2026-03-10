package atn.mill

import mill.*
import mill.scalalib.*
import mill.api.{BuildCtx, PathRef, Task}

/**
 * Mixin for ScalaModules to enable stryker4s mutation testing.
 *
 * Override [[strykerTestModule]] to point to the test module whose classpath
 * and discovered test classes should be used for mutation testing.
 *
 * {{{
 * object example extends ScalaModule with Stryker4sModule {
 *   def scalaVersion      = "3.8.2"
 *   def strykerVersion    = "0.19.1"
 *   def strykerTestModule = test
 *
 *   object test extends ScalaTests with TestModule.Utest {
 *     def mvnDeps = Seq(mvn"com.lihaoyi::utest:0.9.5")
 *   }
 * }
 * }}}
 */
trait Stryker4sModule extends ScalaModule:

  /** Stryker4s version to use (e.g. "0.19.1"). */
  def strykerVersion: String

  /** The test module whose classpath and test classes are used for mutation testing. */
  def strykerTestModule: ScalaTests

  /** Mutation types to exclude from testing. */
  def strykerExcludedMutations: Seq[String] = Seq.empty

  /** Score thresholds for pass/warn/fail. */
  def strykerThresholds: StrykerThresholds = StrykerThresholds()

  /** Report formats to generate. */
  def strykerReporters: Seq[String] = Seq("console", "html", "json")

  /** Number of parallel test runners for mutation testing. */
  def strykerConcurrency: Int = StrykerModule.defaultConcurrency

  /** Scala dialect for the mutator parser. */
  def strykerScalaDialect: String = "scala3future"

  /** Build the stryker4s configuration map (without mutate patterns or base-dir). */
  def strykerConf = Task {
    StrykerModule.buildConf(
      strykerExcludedMutations,
      strykerThresholds,
      strykerReporters,
      strykerConcurrency,
      strykerScalaDialect
    )
  }

  /** Run mutation testing on this module's sources. */
  def strykerMutate() = Task.Command(exclusive = true)[Unit] {
    val workspaceDir = os.pwd
    val dest         = Task.dest
    Task.log.info(s"Stryker4s mutation testing for ${moduleSegments.render}")

    val testCp      = strykerTestModule.runClasspath().map(_.path)
    val framework   = strykerTestModule.testFramework()
    val testClasses = strykerTestModule.discoveredTestClasses()

    // Find the mill-build compiled classes directory (contains StrykerTestRunnerMain)
    val testRunnerClassDir =
      os.Path(classOf[StrykerTestRunnerMain.type].getProtectionDomain.getCodeSource.getLocation.toURI)

    Task.log.info(s"  Test classpath: ${testCp.size} entries")
    Task.log.info(s"  Framework: $framework")
    Task.log.info(s"  Test classes: ${testClasses.mkString(", ")}")
    Task.log.info(s"  Concurrency: $strykerConcurrency")

    // Mirror source files into Task.dest so stryker4s base-dir points here.
    // This way stryker4s writes reports directly to Task.dest/target/ — no post-run cleanup.
    val moduleSources = sources().map(_.path)
    val mirroredSourceDirs = moduleSources.map { srcDir =>
      val rel = srcDir.relativeTo(workspaceDir)
      val destSrcDir = dest / rel
      os.makeDir.all(destSrcDir)
      os.walk(srcDir).filter(_.ext == "scala").foreach { src =>
        val target = destSrcDir / src.relativeTo(srcDir)
        os.makeDir.all(target / os.up)
        os.copy.over(src, target)
      }
      destSrcDir
    }
    val mutatePatterns = mirroredSourceDirs.map { d =>
      d.relativeTo(dest).toString + "/**/*.scala"
    }

    // Write stryker4s config with base-dir pointing to Task.dest.
    val javaCwd    = os.Path(java.nio.file.Path.of("").toAbsolutePath)
    val conf       = strykerConf()
    val moduleConf = conf.updated("mutate", ujson.Arr(mutatePatterns.map(ujson.Str(_))*))
    val confFile   = javaCwd / "stryker4s.conf"
    StrykerModule.writeConf(moduleConf, dest, confFile)

    given stryker4s.log.Logger = new stryker4s.log.Slf4jLogger()

    val moduleScalacOpts = scalacOptions()
    Task.log.info(s"  Scalac options: ${moduleScalacOpts.size} flags")

    val runner = new Stryker4sMillRunner(
      testClasspath = testCp,
      testRunnerClassDir = testRunnerClassDir,
      frameworkName = framework,
      testClasses = testClasses,
      concurrency = strykerConcurrency,
      testTimeout = StrykerModule.defaultTimeout.toLong,
      scalaVersion = scalaVersion(),
      moduleSourceDirs = mirroredSourceDirs,
      scalacOptions = moduleScalacOpts
    )

    try
      import cats.effect.unsafe.implicits.global
      val result = runner.run().unsafeRunSync()
      Task.log.info(s"Mutation testing complete: $result")
    finally os.remove(confFile)
  }

  /**
   * Path to the most recent stryker4s report directory for this module.
   *
   * Stryker4s writes reports to the `strykerMutate` command dest under
   * `target/stryker4s-report/<timestamp>/`.
   */
  /** Path to the most recent stryker4s report directory for this module. */
  private def latestReportDir: os.Path =
    val reportRoot = BuildCtx.workspaceRoot / "out" / moduleSegments.parts / "strykerMutate.dest" / "target" / "stryker4s-report"
    os.list(reportRoot).sortBy(os.mtime(_)).last

  /** Path to the HTML report (if html reporter is configured). */
  def strykerHtmlReport = Task.Input {
    PathRef(latestReportDir / "index.html")
  }

  /** Path to the JSON report (mutation-testing-report.json). */
  def strykerJsonReport = Task.Input {
    PathRef(latestReportDir / "report.json")
  }

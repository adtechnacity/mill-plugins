package atn.mill

import mill.*
import mill.scalalib.*
import mill.api.{Evaluator, PathRef, Result, SelectMode, Task}

trait Stryker4sModule extends ScalaModule:

  def strykerVersion: String

  def strykerMutateGlobs: Seq[String] = Seq("**/src/**/*.scala")

  def strykerExcludedMutations: Seq[String] = Seq.empty

  def strykerThresholds: StrykerThresholds = StrykerThresholds()

  def strykerReporters: Seq[String] = Seq("console", "html", "json")

  def strykerConcurrency: Int = StrykerModule.defaultConcurrency

  def strykerScalaDialect: String = "scala3future"

  def strykerConf = Task {
    StrykerModule.buildConf(
      strykerMutateGlobs,
      strykerExcludedMutations,
      strykerThresholds,
      strykerReporters,
      strykerConcurrency,
      strykerScalaDialect
    )
  }

  /** Run mutation testing using the external command runner (subprocess per mutation). */
  def strykerMutateExternal(evaluator: Evaluator) = Task.Command(exclusive = true)[Unit] {
    val conf         = strykerConf()
    // Mill always runs from workspace root, so os.pwd is the workspace root
    val workspaceDir = os.pwd
    val confFile     = Task.dest / "stryker4s.conf"

    // Determine the module path for the test command
    val modulePath = moduleSegments.render
    val testCmd    = s"$modulePath.test"

    // Override mutate globs to target this module's sources, relative to workspace root
    val moduleConf = conf.updated(
      "mutate",
      ujson.Arr(sources().map(pr => ujson.Str(pr.path.relativeTo(workspaceDir).toString + "/**/*.scala"))*)
    )

    // Create a wrapper script for the stryker4s test runner.
    // Stryker4s copies the workspace to a temp dir and runs the test command
    // from there. The script must:
    // 1. Remove the copied out/mill-daemon (conflicts with the active daemon)
    // 2. Disable scoverage — instrumentation on mutated code causes
    //    "Method too large" JVM errors
    // 3. Run Mill with --no-daemon and a timeout to prevent hanging tests
    val timeoutSecs   = StrykerModule.defaultTimeout / 1000
    val wrapperScript = workspaceDir / "stryker-test-runner.sh"
    os.write.over(
      wrapperScript,
      s"""|#!/usr/bin/env bash
          |set -e
          |rm -rf out/mill-daemon out/mill-build
          |export DISABLE_SCOVERAGE=1
          |exec timeout ${timeoutSecs}s ./mill --no-daemon $testCmd
          |""".stripMargin
    )
    os.perms.set(wrapperScript, "rwxr-xr-x")

    // Use relative path — stryker4s runs the command from its temp copy
    StrykerModule.writeConf(moduleConf, workspaceDir, confFile, testCmd, "./stryker-test-runner.sh")
    Task.log.info(s"Stryker4s config written to $confFile")

    val classpath    = StrykerModule.resolveStrykerClasspath(strykerVersion)
    // Copy config to workspace root since command-runner looks for it in base-dir
    val rootConfFile = workspaceDir / "stryker4s.conf"
    os.copy.over(confFile, rootConfFile)
    try
      StrykerModule.runStryker(classpath, workspaceDir, Task.log)
    finally
      collectReports(workspaceDir, Task.dest)
      os.remove(rootConfFile)
      os.remove(wrapperScript)
  }

  /** Run mutation testing on this module's sources. */
  def strykerMutate(evaluator: Evaluator) = Task.Command(exclusive = true)[Unit] {
    val modulePath   = moduleSegments.render
    val workspaceDir = os.pwd
    Task.log.info(s"Native stryker4s runner for $modulePath")

    // Resolve test classpath from the module's test sub-module
    val testCp = resolveEvaluatorTask[Seq[PathRef]](evaluator, s"$modulePath.test.runClasspath")
      .map(_.path)

    // Resolve test framework name
    val framework = resolveEvaluatorTask[String](evaluator, s"$modulePath.test.testFramework")

    // Resolve discovered test class names
    val testClasses = resolveEvaluatorTask[Seq[String]](evaluator, s"$modulePath.test.discoveredTestClasses")

    // Find the mill-build compiled classes directory (contains StrykerTestRunnerMain)
    val testRunnerClassDir =
      os.Path(classOf[StrykerTestRunnerMain.type].getProtectionDomain.getCodeSource.getLocation.toURI)

    Task.log.info(s"  Test classpath: ${testCp.size} entries")
    Task.log.info(s"  Framework: $framework")
    Task.log.info(s"  Test classes: ${testClasses.mkString(", ")}")
    Task.log.info(s"  Concurrency: $strykerConcurrency")

    // Compute mutate patterns from module sources, relative to workspace root
    val mutatePatterns = sources().map(pr => pr.path.relativeTo(workspaceDir).toString + "/**/*.scala")

    // Write stryker4s config to JVM CWD (Mill sandbox), where FileConfigSource reads it.
    // Mill's os.pwd differs from the JVM's actual CWD — stryker4s uses the JVM CWD.
    val javaCwd    = os.Path(java.nio.file.Path.of("").toAbsolutePath)
    val conf       = strykerConf()
    val moduleConf = conf.updated("mutate", ujson.Arr(mutatePatterns.map(ujson.Str(_))*))
    val confFile   = javaCwd / "stryker4s.conf"
    StrykerModule.writeConf(moduleConf, workspaceDir, confFile, testCommand = "", testRunnerCommand = "")

    given stryker4s.log.Logger = new stryker4s.log.Slf4jLogger()

    // Resolve module's scalac options for compiling instrumented sources
    val moduleScalacOpts = resolveEvaluatorTask[Seq[String]](evaluator, s"$modulePath.scalacOptions")
    Task.log.info(s"  Scalac options: ${moduleScalacOpts.size} flags")

    val runner = new Stryker4sMillRunner(
      testClasspath = testCp,
      testRunnerClassDir = testRunnerClassDir,
      frameworkName = framework,
      testClasses = testClasses,
      concurrency = strykerConcurrency,
      testTimeout = StrykerModule.defaultTimeout.toLong,
      scalaVersion = scalaVersion(),
      moduleSourceDirs = sources().map(_.path),
      scalacOptions = moduleScalacOpts
    )

    try
      import cats.effect.unsafe.implicits.global
      val result = runner.run().unsafeRunSync()
      Task.log.info(s"Native mutation testing complete: $result")
    finally
      collectReports(workspaceDir, Task.dest)
      os.remove(confFile)
  }

  /** Resolve a single task from the evaluator and extract its value. */
  private def resolveEvaluatorTask[T](evaluator: Evaluator, taskSelector: String): T =
    evaluator
      .resolveTasks(Seq(taskSelector), SelectMode.Multi)
      .toEither
      .fold(
        e => throw new RuntimeException(s"Could not resolve $taskSelector: $e"),
        tasks =>
          evaluator
            .execute(tasks.asInstanceOf[Seq[Task[Any]]])
            .executionResults
            .results
            .head
            .get
            .value
            .asInstanceOf[T]
      )

  /**
   * Path to the most recent stryker4s report directory for this module.
   *
   * During mutation runs, `<workspace>/target` is symlinked to `Task.dest` so stryker4s
   * writes reports directly into Mill's `out/` directory.
   */
  def strykerReportDir = Task {
    val reportDirs = os
      .list(Task.dest)
      .filter(p => os.isDir(p) && p.last.startsWith("stryker4s-report"))
      .sortBy(os.mtime(_))
      .reverse
    reportDirs.headOption match
      case Some(dir) => PathRef(dir)
      case None      => PathRef(Task.dest)
  }

  /** Path to the HTML report (if html reporter is configured). */
  def strykerHtmlReport = Task {
    PathRef(strykerReportDir().path)
  }

  /** Path to the JSON report (mutation-testing-report.json). */
  def strykerJsonReport = Task {
    val dir  = strykerReportDir().path
    val json = dir / "report.json"
    PathRef(if os.exists(json) then json else dir)
  }

  /** Move stryker4s report directories from `<workspace>/target/` into dest, cleaning up if empty. */
  private def collectReports(workspaceDir: os.Path, dest: os.Path): Unit =
    val targetDir = workspaceDir / "target"
    if os.exists(targetDir) then
      os.list(targetDir)
        .filter(p => os.isDir(p) && p.last.startsWith("stryker4s-report"))
        .foreach(reportDir => os.move(reportDir, dest / reportDir.last))
      if os.list(targetDir).isEmpty then os.remove(targetDir)

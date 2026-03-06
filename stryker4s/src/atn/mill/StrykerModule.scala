package atn.mill

import mill.*
import mill.api.{DefaultTaskModule, Discover, Evaluator, ExternalModule, Result, Task}
import mill.api.PathRef

final case class StrykerThresholds(high: Int = 80, low: Int = 60, break: Int = 0)

object StrykerModule:

  val defaultConcurrency: Int =
    (Runtime.getRuntime.availableProcessors().toDouble / 4).round.toInt.max(1) + 1

  val defaultTimeout: Int = 300000

  def buildConf(
    mutateGlobs: Seq[String],
    excludedMutations: Seq[String],
    thresholds: StrykerThresholds,
    reporters: Seq[String],
    concurrency: Int,
    scalaDialect: String
  ): Map[String, ujson.Value]             =
    Map(
      "mutate"             -> ujson.Arr(mutateGlobs.map(ujson.Str(_))*),
      "excluded-mutations" -> ujson.Arr(excludedMutations.map(ujson.Str(_))*),
      "thresholds"         -> ujson.Obj("high" -> thresholds.high, "low" -> thresholds.low, "break" -> thresholds.break),
      "reporters"          -> ujson.Arr(reporters.map(ujson.Str(_))*),
      "concurrency"        -> concurrency,
      "scala-dialect"      -> scalaDialect,
      "timeout"            -> defaultTimeout
    )

  def writeConf(
    conf: Map[String, ujson.Value],
    baseDir: os.Path,
    confFile: os.Path,
    testCommand: String = "",
    testRunnerCommand: String = "./mill"
  ): Unit =
    val inner   = ujson.Obj.from(conf)
    inner("base-dir") = baseDir.toString
    inner("test-runner") = ujson.Obj("command" -> testRunnerCommand, "args" -> testCommand)
    val wrapper = ujson.Obj("stryker4s" -> inner)
    os.write.over(confFile, ujson.write(wrapper, indent = 2))

  /** Resolve the stryker4s-command-runner classpath via Coursier. */
  @annotation.nowarn("msg=deprecated")
  def resolveStrykerClasspath(version: String): Seq[os.Path] =
    coursier
      .Fetch()
      .addDependencies(
        coursier.Dependency(
          coursier.Module(coursier.Organization("io.stryker-mutator"), coursier.ModuleName("stryker4s-command-runner_3")),
          version
        )
      )
      .run()
      .toSeq
      .map(f => os.Path(f))

  /**
   * Execute stryker4s as a subprocess with the resolved classpath.
   *
   * stryker4s-command-runner reads config from `stryker4s.conf` in the cwd (base-dir).
   */
  def runStryker(classpath: Seq[os.Path], baseDir: os.Path, log: mill.api.Logger): Unit =
    val cp      = classpath.mkString(java.io.File.pathSeparator)
    val javaBin = os.Path(sys.props("java.home")) / "bin" / "java"

    log.info(s"Running stryker4s mutation testing")
    val result = os
      .proc(javaBin.toString, "-cp", cp, "stryker4s.command.Stryker4sMain")
      .call(cwd = baseDir, stdout = os.Inherit, stderr = os.Inherit, check = false)

    if (result.exitCode != 0)
      throw new RuntimeException(s"Stryker4s mutation testing failed with exit code ${result.exitCode}")
    log.info("Mutation testing complete")

  def filterScalacOptions(opts: Seq[String]): Seq[String] =
    opts.filterNot(opt => opt == "-Xfatal-warnings" || opt.contains("unused"))

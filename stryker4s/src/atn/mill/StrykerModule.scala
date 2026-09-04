package atn.mill

import mill.*
import mill.api.PathRef

final case class StrykerThresholds(high: Int = 80, low: Int = 60, break: Int = 0)

object StrykerModule:

  val defaultConcurrency: Int =
    (Runtime.getRuntime.availableProcessors().toDouble / 4).round.toInt.max(1) + 1

  val defaultTimeout: Int = 300000

  def buildConf(
    excludedMutations: Seq[String],
    thresholds: StrykerThresholds,
    reporters: Seq[String],
    concurrency: Int,
    scalaDialect: String
  ): Map[String, ujson.Value] =
    Map(
      "excluded-mutations" -> ujson.Arr(excludedMutations.map(ujson.Str(_))*),
      "thresholds"         -> ujson.Obj("high" -> thresholds.high, "low" -> thresholds.low, "break" -> thresholds.break),
      "reporters"          -> ujson.Arr(reporters.map(ujson.Str(_))*),
      "concurrency"        -> concurrency,
      "scala-dialect"      -> scalaDialect,
      "timeout"            -> defaultTimeout
    )

  /** Write a stryker4s config file. `base-dir` is set but may be overridden by `extraConfigSources`. */
  def writeConf(conf: Map[String, ujson.Value], baseDir: os.Path, confFile: os.Path): Unit =
    val inner   = ujson.Obj.from(conf)
    inner("base-dir") = baseDir.toString
    val wrapper = ujson.Obj("stryker4s" -> inner)
    os.write.over(confFile, ujson.write(wrapper, indent = 2))

  /**
   * The `mutate` patterns for a module: one include glob per mirrored source root, then one `!`-prefixed exclude per
   * entry in `strykerExcludedFiles`. Stryker4s's `Glob.matcher` partitions on that `!` prefix and treats the remainder
   * as a negative match, so an excluded file is skipped without dropping a mutator repo-wide.
   */
  def mutatePatterns(sourceRoots: Seq[String], excludedFiles: Seq[String]): Seq[String] =
    sourceRoots.map(_ + "/**/*.scala") ++ excludedFiles.map("!" + _)

  /**
   * The compiler artifact for a Scala version. Scala 3 publishes `scala3-compiler_3`; Scala 2 publishes an unsuffixed
   * `scala-compiler`. Asking for `scala3-compiler_3` at a 2.13.x version resolves nothing and aborts the run before any
   * mutant is instrumented.
   */
  def compilerArtifactName(scalaVersion: String): String =
    if scalaVersion.startsWith("3") then "scala3-compiler_3" else "scala-compiler"

  /**
   * The compiler entry point for a Scala version: Scala 3 compiles through `dotty.tools.dotc.Main`, Scala 2 through
   * `scala.tools.nsc.Main`. Paired with [[compilerArtifactName]] - resolving the right jar is not enough if the main
   * class invoked on it belongs to the other compiler.
   */
  def compilerMainClass(scalaVersion: String): String =
    if scalaVersion.startsWith("3") then "dotty.tools.dotc.Main" else "scala.tools.nsc.Main"

  def filterScalacOptions(opts: Seq[String]): Seq[String] =
    opts.filterNot(opt => opt == "-Xfatal-warnings" || opt.contains("unused"))

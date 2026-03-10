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

  def filterScalacOptions(opts: Seq[String]): Seq[String] =
    opts.filterNot(opt => opt == "-Xfatal-warnings" || opt.contains("unused"))

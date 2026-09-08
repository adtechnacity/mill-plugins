package atn.mill

import mill.*
import mill.api.PathRef
import stryker4s.model.CompilerErrMsg

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
    mutatePatterns(sourceRoots, Seq.empty, excludedFiles)

  /**
   * As the two-argument `mutatePatterns`, narrowed to `includedFiles` when that is non-empty: the positive globs
   * (relative to the workspace root, e.g. the files a pull request touched) replace the per-source-root patterns (every
   * `.scala` file under each root), and the excludes still follow as `!` negations.
   */
  def mutatePatterns(sourceRoots: Seq[String], includedFiles: Seq[String], excludedFiles: Seq[String]): Seq[String] =
    val includes = if includedFiles.isEmpty then sourceRoots.map(_ + "/**/*.scala") else includedFiles
    includes ++ excludedFiles.map("!" + _)

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

  private val ansiCode     = "\u001b\\[[0-9;]*m".r
  private val scala3Header = """^-- (?:\[E\d+\] )?(.*?[Ee]rror): (.+?):(\d+):(\d+)(?:\s.*)?$""".r
  private val scala2Error  = """^(.+?):(\d+): error: (.*)$""".r
  private val caretLine    = """^\s*\|\s*\^+\s*$""".r

  /**
   * Parse scalac output into one [[stryker4s.model.CompilerErrMsg]] per reported error, which is what lets stryker4s
   * roll back only the mutants that do not compile instead of aborting the whole module: its `RollbackHandler` matches
   * `mutatedFile.fileOrigin.toString.endsWith(err.path)` and compares the 1-based `line` with the mutation switch case
   * statements of the mutated source.
   *
   * The path is made relative to `sourceDir` (stryker4s's tmp copy of the sources), so it is a suffix of the mirrored
   * `fileOrigin` under `Task.dest`; a path outside `sourceDir` is kept verbatim. Scala 3 blocks (`-- [E008] Not Found
   * Error: <path>:<line>:<col> ---`, message on the line after the caret line) and Scala 2 lines (`<path>:<line>:
   * error: <message>`) are recognised; warnings and ANSI colour codes are ignored.
   */
  def parseCompilerErrors(output: String, sourceDir: os.Path): Seq[CompilerErrMsg] =
    val lines = ansiCode.replaceAllIn(output, "").linesIterator.toVector
    lines.zipWithIndex.collect {
      case (scala3Header(kind, path, line, _), index) =>
        CompilerErrMsg(scala3Message(lines.drop(index + 1)).getOrElse(kind), relativeTo(path, sourceDir), line.toInt)
      case (scala2Error(path, line, message), _)      =>
        CompilerErrMsg(message.trim, relativeTo(path, sourceDir), line.toInt)
    }

  /** The first message line of a Scala 3 diagnostic block: the `|`-prefixed line after the caret line. */
  private def scala3Message(rest: Seq[String]): Option[String] =
    rest
      .takeWhile(line => !line.startsWith("-- "))
      .dropWhile(line => !caretLine.matches(line))
      .drop(1)
      .headOption
      .map(_.dropWhile(_ != '|').drop(1).trim)
      .filter(_.nonEmpty)

  private def relativeTo(path: String, sourceDir: os.Path): String =
    os.FilePath(path) match
      case p: os.Path if p.startsWith(sourceDir) => p.relativeTo(sourceDir).toString
      case _                                     => path

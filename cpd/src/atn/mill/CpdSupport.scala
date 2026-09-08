package atn.mill

import mill.*
import mill.api.{BuildCtx, ModuleRef, Result, TaskCtx}
import mill.scalalib.*
import mill.util.Jvm

/**
 * Workspace-wide copy-paste detection with PMD's CPD, mixed into one nested object of `build.mill`:
 * {{{
 * import mill.api.ModuleRef
 *
 * object cpd extends CpdSupport {
 *   def cpdRootModule = ModuleRef(build) // discovers every JavaModule in the build
 * }
 * }}}
 *
 * `./mill cpd` (the default task, [[cpdCheckAll]]) runs CPD once per language in [[cpdLanguages]] over the `sources()`
 * of every `JavaModule` reachable from [[cpdRootModule]] — test modules included — so duplication ''between'' modules
 * is found, which per-module runs cannot see. Duplications of at least [[cpdMinimumTokens]] tokens are warnings (echoed
 * and counted; the count is the task's value); duplications of at least [[cpdErrorTokens]] tokens are errors and fail
 * the task. Exclude paths with [[cpdExcludes]], or fence intentional duplicates with `// CPD-OFF` / `// CPD-ON` in the
 * source.
 *
 * Brownfield adoption is a threshold ratchet: start with a high [[cpdErrorTokens]], read `./mill show cpd` and the CSV
 * reports, fence or fix the duplicates that matter, then lower the threshold. The task is cached, so an unchanged
 * workspace is not rescanned and `./mill show cpd` prints the count from cache; failures are never cached.
 *
 * PMD runs in a subprocess (`net.sourceforge.pmd.cli.PmdCli`) on a classpath resolved from [[pmdVersion]] and
 * [[cpdMvnDeps]], with the build's environment. Tested against PMD 7.27.0; other 7.x versions are expected to work.
 */
trait CpdSupport extends DefaultTaskModule with CoursierModule:

  /**
   * Root of the module tree to scan, wrapped in a [[mill.api.ModuleRef]] so that Mill's resolver does not treat the
   * reference back to the root as a child of this module (a bare `Module` reference breaks every `__` selector with a
   * cyclic-module error). In `build.mill`: `def cpdRootModule = ModuleRef(build)`.
   */
  def cpdRootModule: ModuleRef[Module]

  /** `./mill cpd` runs [[cpdCheckAll]]. */
  def defaultTask(): String = "cpdCheckAll"

  /** PMD version used for the CLI and the language modules. Defaults to the version this plugin is tested with. */
  def pmdVersion: String = "7.27.0"

  /**
   * CPD language ids, one CPD run and one report each; `scala` and `java` by default. Extend with e.g.
   * `super.cpdLanguages :+ "kotlin"`: the matching PMD language module is added to the classpath by [[cpdMvnDeps]].
   */
  def cpdLanguages: Seq[String] = Seq("scala", "java")

  /**
   * PMD language modules resolved onto the CPD classpath: `net.sourceforge.pmd:pmd-<language>:<pmdVersion>` for every
   * entry of [[cpdLanguages]] (`scala` maps to `pmd-scala_2.13`). Override for artifacts that do not follow that
   * naming.
   */
  def cpdMvnDeps: T[Seq[Dep]] = Task {
    cpdLanguages.map {
      case "scala"  => mvn"net.sourceforge.pmd:pmd-scala_2.13:$pmdVersion"
      case language => mvn"net.sourceforge.pmd:pmd-$language:$pmdVersion"
    }
  }

  /** `pmd-cli` plus [[cpdMvnDeps]]: the classpath of the CPD subprocess. */
  private def cpdClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(Seq(mvn"net.sourceforge.pmd:pmd-cli:$pmdVersion") ++ cpdMvnDeps())
  }

  /**
   * Modules whose `sources()` are scanned: every `JavaModule` (Scala, Java, Kotlin, test modules, ...) reachable from
   * [[cpdRootModule]]. Drop modules with e.g. `super.cpdModules.filterNot(_ == generated)`.
   */
  def cpdModules: Seq[JavaModule] = cpdRootModule().moduleInternal.modules.collect { case m: JavaModule => m }

  /** Warning tier: duplications of at least this many tokens are reported and counted. */
  def cpdMinimumTokens: Int = 25

  /**
   * Error tier: duplications of at least this many tokens fail [[cpdCheckAll]]. Must be at least [[cpdMinimumTokens]].
   */
  def cpdErrorTokens: Int = 75

  /**
   * Files or directories (directories recursively) left out of the scan, relative to the workspace root, e.g.
   * `Seq("b/src/generated")`. Literal paths only: PMD's `--exclude` takes no globs.
   */
  def cpdExcludes: Seq[String] = Seq.empty

  /**
   * Extra arguments passed verbatim to PMD's `cpd` command, e.g. `Seq("--ignore-literals", "--skip-duplicate-files")`.
   * They go before the `--exclude` and `--dir` lists, so an unknown flag is a usage error rather than a stray input
   * path.
   */
  def cpdOptions: Seq[String] = Seq.empty

  /**
   * Runs CPD once per language over every existing source directory of [[cpdModules]] and returns the number of
   * warnings: duplications of at least [[cpdMinimumTokens]] but fewer than [[cpdErrorTokens]] tokens, each echoed at
   * warn level. Fails when a duplication reaches [[cpdErrorTokens]] (the offending report rows are the message), when
   * [[cpdErrorTokens]] is below [[cpdMinimumTokens]], or when PMD exits non-zero (PMD's own output is the message). PMD
   * runs with `--no-fail-on-error`, so a file it cannot tokenize is logged and skipped instead of failing the run.
   *
   * One CSV report per language (`lines,tokens,occurrences` followed by one row per duplication) is written to
   * `out/cpd/cpdCheckAll.dest/cpd-<language>.csv` and stays there after a failure until the next execution. With no
   * source directories at all the value is 0 and PMD is not started.
   */
  def cpdCheckAll: T[Int] = Task {
    val sourceDirs = Task.traverse(cpdModules)(_.sources)().flatten.map(_.path).filter(os.exists).distinct
    val classpath  = cpdClasspath().map(_.path)
    if cpdErrorTokens < cpdMinimumTokens then
      Result.Failure(s"cpdErrorTokens ($cpdErrorTokens) must be at least cpdMinimumTokens ($cpdMinimumTokens)")
    else if sourceDirs.isEmpty then Result.Success(0)
    else
      cpdLanguages.foldLeft[Result[Int]](Result.Success(0)) { (total, language) =>
        total.flatMap(count => runCpd(language, classpath, sourceDirs).map(_ + count))
      }
  }

  /** One `pmd cpd` subprocess for `language`; the report rows are partitioned into warnings and errors. */
  private def runCpd(language: String, classpath: Seq[os.Path], sourceDirs: Seq[os.Path])(using
    ctx: TaskCtx
  ): Result[Int] =
    val report = Task.dest / s"cpd-$language.csv"
    val args   =
      Seq(
        "cpd",
        "--language",
        language,
        "--minimum-tokens",
        cpdMinimumTokens.toString,
        "--no-fail-on-violation",
        "--no-fail-on-error",
        "-f",
        "csv",
        "-r",
        report.toString
      )
        ++ cpdOptions
        ++ cpdExcludes.flatMap(p => Seq("--exclude", (BuildCtx.workspaceRoot / os.RelPath(p)).toString))
        ++ sourceDirs.flatMap(dir => Seq("-d", dir.toString))
    // A cached task may only write under Task.dest; Mill's sandbox counts a subprocess started with the workspace
    // as its cwd as a write there, hence the same escape hatch Mill's own JavaModule and RunModule use. PMD itself
    // reads the source directories and writes only the report under Task.dest.
    val run    = BuildCtx.withFilesystemCheckerDisabled {
      Jvm.callProcess(
        mainClass = "net.sourceforge.pmd.cli.PmdCli",
        mainArgs = args,
        jvmArgs = Seq("-Dfile.encoding=UTF-8"),
        classPath = classpath,
        cwd = BuildCtx.workspaceRoot,
        stdout = os.Pipe,
        mergeErrIntoOut = true,
        check = false
      )
    }
    val output = run.out.text().trim
    if run.exitCode != 0 then Result.Failure(s"CPD ($language) exited ${run.exitCode}\n$output")
    else
      if output.nonEmpty then Task.log.warn(output)
      val (errors, warnings) =
        os.read.lines(report).drop(1).partition(_.split(',')(1).toInt >= cpdErrorTokens)
      if errors.nonEmpty then
        Result.Failure(s"CPD ($language): ${errors.size} duplication(s) at or above $cpdErrorTokens tokens:\n${errors.mkString("\n")}")
      else
        warnings.foreach(row => Task.log.warn(s"CPD ($language) duplication: $row"))
        Result.Success(warnings.size)

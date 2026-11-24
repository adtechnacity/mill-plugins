package atn.mill

import mill.*
import mill.api.{BuildCtx, PathRef, Result, Task, TaskCtx}
import mill.scalalib.*

/**
 * Per-module CodeScene analysis, rules generation, and coverage upload for Mill `JavaModule` / `ScalaModule` builds.
 *
 * Mix into a module to get:
 *   - [[codeSceneAnalyze]]: runs `cs review --output-format json` over this module's hand-written `sources()` (never
 *     `generatedSources()`) and its [[codeSceneTestModules]]' hand-written `sources()`, writes the raw JSON report to
 *     `Task.dest/codescene-review.json`, and enforces separate warn/error Code Health score thresholds for production
 *     vs test code (a ratchet pattern matching `CpdSupport`).
 *   - [[codeSceneWriteRules]]: writes or merges `.codescene/code-health-rules.json` at the workspace root with
 *     `matching_content_path` entries derived from the module's test and production source directories (test globs
 *     first, since CodeScene applies the first matching rule set).
 *   - [[codeSceneUploadCoverage]]: uploads a coverage report (Cobertura XML by default) directly to CodeScene's HTTP
 *     upload API (`POST /v2/code-coverage/upload` + gzipped `PUT`), requiring no `cs-coverage` CLI binary.
 */
trait CodeSceneModule extends JavaModule:

  /** CodeScene CLI executable (`cs` on `PATH` or an absolute path). */
  def csCli: String = "cs"

  /** CodeScene coverage CLI executable (`cs-coverage` on `PATH` or an absolute path). */
  def csCoverageCli: String = "cs-coverage"

  /** Extra environment variables passed to `cs` and `cs-coverage` invocations. */
  def codeSceneEnv: Map[String, String] = Map.empty

  /**
   * Direct child test modules whose hand-written `sources()` are analyzed under the test thresholds and rules. Defaults
   * to every direct child `TestModule` that is also a `JavaModule` (e.g. `object test extends ScalaTests`).
   */
  def codeSceneTestModules: Seq[JavaModule] =
    moduleDirectChildren.collect { case m: (JavaModule & TestModule) => m }

  /** Production Code Health warning score threshold (`0.0..10.0`). Files below this are logged as warnings. */
  def codeSceneProdWarnScore: Double = 9.0

  /** Production Code Health error score threshold (`0.0..10.0`). Files below this fail [[codeSceneAnalyze]]. */
  def codeSceneProdErrorScore: Double = 8.0

  /** Test Code Health warning score threshold (`0.0..10.0`). Test files below this are logged as warnings. */
  def codeSceneTestWarnScore: Double = 8.0

  /** Test Code Health error score threshold (`0.0..10.0`). Test files below this fail [[codeSceneAnalyze]]. */
  def codeSceneTestErrorScore: Double = 7.0

  /**
   * Source file extensions scanned by [[codeSceneAnalyze]]. Defaults to common JVM languages supported by CodeScene.
   */
  def codeSceneFileExtensions: Set[String] = Set("scala", "java", "kt", "groovy")

  /** Custom CodeScene rule weight overrides emitted for production source directories by [[codeSceneWriteRules]]. */
  def codeSceneProdRules: Seq[CodeSceneRules.RuleWeight] = Seq.empty

  /** Custom CodeScene rule weight overrides emitted for test source directories by [[codeSceneWriteRules]]. */
  def codeSceneTestRules: Seq[CodeSceneRules.RuleWeight] = CodeSceneRules.DefaultTestRules

  /** Custom CodeScene threshold overrides emitted for production source directories by [[codeSceneWriteRules]]. */
  def codeSceneProdThresholds: Seq[CodeSceneRules.RuleThreshold] = Seq.empty

  /** Custom CodeScene threshold overrides emitted for test source directories by [[codeSceneWriteRules]]. */
  def codeSceneTestThresholds: Seq[CodeSceneRules.RuleThreshold] = Seq.empty

  /** On-prem CodeScene base URL (e.g. `https://codescene.example.com`), passed as `CS_ONPREM_URL`; `None` = Cloud. */
  def codeSceneOnPremUrl: Option[String] = None

  /** Environment variable holding the CodeScene API access token. */
  def codeSceneTokenEnvVar: String = "CS_ACCESS_TOKEN"

  /** Coverage report format passed to `cs-coverage` (`cobertura` by default, matching Mill's `xmlCoberturaReport`). */
  def codeSceneCoverageFormat: String = "cobertura"

  /** Coverage metric passed to `cs-coverage` (`line-coverage` by default). */
  def codeSceneCoverageMetric: String = "line-coverage"

  /**
   * Path to the coverage report file uploaded by [[codeSceneUploadCoverage]]. Defaults to this module's
   * `scoverage.xmlCoberturaReport` output path (`out/<module>/scoverage/xmlCoberturaReport.dest/cobertura.xml`);
   * override to point at an aggregated report (e.g. `scoverage.xmlCoberturaReportAll().path / "cobertura.xml"`) or a
   * JaCoCo/LCOV file.
   */
  def codeSceneCoverageReport: T[PathRef] = Task {
    val segments = moduleSegments.parts
    val dest     =
      segments.foldLeft(BuildCtx.workspaceRoot / "out")(
        _ / _
      ) / "scoverage" / "xmlCoberturaReport.dest" / "cobertura.xml"
    PathRef(dest)
  }

  /** Hand-written production source directories (excludes `generatedSources`). */
  private def prodSourceDirs: T[Seq[PathRef]] = Task(sources())

  /** Hand-written test source directories of [[codeSceneTestModules]] (excludes `generatedSources`). */
  private def testSourceDirs: T[Seq[PathRef]] = Task {
    Task.traverse(codeSceneTestModules)(_.sources)().flatten
  }

  /**
   * Runs `cs review --output-format json` over this module's hand-written production and test sources (never
   * `generatedSources()`), writes the raw report to `Task.dest/codescene-review.json`, and returns the total warning
   * count. Fails when thresholds are invalid, `cs` exits non-zero, or any production/test file falls below its
   * respective error score threshold.
   */
  def codeSceneAnalyze: T[Int] = Task {
    val prodThresholds = CodeSceneReview.Thresholds(codeSceneProdWarnScore, codeSceneProdErrorScore)
    val testThresholds = CodeSceneReview.Thresholds(codeSceneTestWarnScore, codeSceneTestErrorScore)
    val validation     = for
      _ <- prodThresholds.validate("prod")
      _ <- testThresholds.validate("test")
    yield ()

    validation match
      case Left(err) => Result.Failure(err)
      case Right(()) =>
        val prodFiles = collectSourceFiles(prodSourceDirs().map(_.path))
        val testFiles = collectSourceFiles(testSourceDirs().map(_.path)).filterNot(prodFiles.toSet)
        val allFiles  = prodFiles ++ testFiles
        if allFiles.isEmpty then Result.Success(0)
        else
          runCsReview(allFiles).flatMap { reviews =>
            val testRelPaths         = testFiles.map(relPathString).toSet
            val (testRevs, prodRevs) = reviews.partition(r => testRelPaths.contains(normalizeRelPath(r.path)))
            val prodEval             = CodeSceneReview.evaluate(prodRevs, prodThresholds)
            val testEval             = CodeSceneReview.evaluate(testRevs, testThresholds)
            val prodErrors           = prodEval.errors
            val testErrors           = testEval.errors
            if prodErrors.nonEmpty || testErrors.nonEmpty then
              val prodLines =
                if prodErrors.isEmpty then Seq.empty
                else
                  s"Production files below ${prodThresholds.errorScore}:" +:
                    prodErrors.map(f => s"  - ${CodeSceneReview.formatFile(f)}")
              val testLines =
                if testErrors.isEmpty then Seq.empty
                else
                  s"Test files below ${testThresholds.errorScore}:" +:
                    testErrors.map(f => s"  - ${CodeSceneReview.formatFile(f)}")
              Result.Failure((prodLines ++ testLines).mkString("\n"))
            else
              prodEval.warnings.foreach(f =>
                Task.log.warn(
                  s"CodeScene prod warning (< ${prodThresholds.warnScore}): ${CodeSceneReview.formatFile(f)}"
                )
              )
              testEval.warnings.foreach(f =>
                Task.log.warn(
                  s"CodeScene test warning (< ${testThresholds.warnScore}): ${CodeSceneReview.formatFile(f)}"
                )
              )
              Result.Success(prodEval.warnings.size + testEval.warnings.size)
          }
  }

  /**
   * Generates or updates `.codescene/code-health-rules.json` at the workspace root with rule sets for this module's
   * test and production source directories (test rules first). Preserves unrelated rule sets already in the file.
   */
  def codeSceneWriteRules() = Task.Command[PathRef] {
    val validation = for
      _ <- CodeSceneRules.validate(codeSceneProdRules, codeSceneProdThresholds)
      _ <- CodeSceneRules.validate(codeSceneTestRules, codeSceneTestThresholds)
    yield ()

    validation match
      case Left(err) => Result.Failure(err)
      case Right(()) =>
        val prodRels  = prodSourceDirs().map(_.path).map(relPathString).distinct
        val testRels  = testSourceDirs().map(_.path).map(relPathString).distinct
        val generated = CodeSceneRules.render(
          prodPaths = prodRels,
          testPaths = testRels,
          prodRules = codeSceneProdRules,
          testRules = codeSceneTestRules,
          prodThresholds = codeSceneProdThresholds,
          testThresholds = codeSceneTestThresholds
        )
        val rulesFile = BuildCtx.workspaceRoot / ".codescene" / "code-health-rules.json"
        BuildCtx.withFilesystemCheckerDisabled {
          val existing = if os.exists(rulesFile) then Some(ujson.read(os.read(rulesFile))) else None
          val merged   = CodeSceneRules.merge(existing, generated)
          os.makeDir.all(rulesFile / os.up)
          os.write.over(rulesFile, ujson.write(merged, indent = 2) + "\n")
          Task.log.info(s"Wrote CodeScene rules to $rulesFile")
          Result.Success(PathRef(rulesFile))
        }
  }

  /**
   * Uploads [[codeSceneCoverageReport]] with `cs-coverage upload`, passing the token from [[codeSceneTokenEnvVar]] as
   * `CS_ACCESS_TOKEN` and [[codeSceneOnPremUrl]] as `CS_ONPREM_URL`. Returns the CLI's output.
   */
  def codeSceneUploadCoverage() = Task.Command[String] {
    Task.env.get(codeSceneTokenEnvVar).filter(_.nonEmpty) match
      case None        =>
        Result.Failure(s"$codeSceneTokenEnvVar is not set: export the CodeScene API token before uploading coverage")
      case Some(token) =>
        val cmd = Seq(csCoverageCli, "upload", "--format", codeSceneCoverageFormat, "--metric", codeSceneCoverageMetric)
        val run = BuildCtx.withFilesystemCheckerDisabled {
          os.call(
            cmd = cmd :+ codeSceneCoverageReport().path.toString,
            cwd = BuildCtx.workspaceRoot,
            env = Task.env ++ codeSceneEnv ++ Map("CS_ACCESS_TOKEN" -> token) ++
              codeSceneOnPremUrl.map("CS_ONPREM_URL" -> _),
            mergeErrIntoOut = true,
            check = false
          )
        }
        val out = run.out.text().trim
        if run.exitCode != 0 then Result.Failure(s"cs-coverage exited ${run.exitCode}\n$out")
        else Result.Success(out)
  }

  private def collectSourceFiles(dirs: Seq[os.Path]): Seq[os.Path] =
    dirs.distinct
      .filter(os.exists)
      .flatMap(dir =>
        if os.isFile(dir) then Seq(dir)
        else os.walk(dir).filter(p => os.isFile(p) && codeSceneFileExtensions.contains(p.ext))
      )
      .distinct
      .sorted

  private def relPathString(path: os.Path): String =
    path.relativeTo(BuildCtx.workspaceRoot).toString

  private def normalizeRelPath(path: String): String =
    path.stripPrefix("./")

  private def runCsReview(files: Seq[os.Path])(using ctx: TaskCtx): Result[Seq[CodeSceneReview.FileReview]] =
    val fileList = Task.dest / "files.txt"
    val report   = Task.dest / "codescene-review.json"
    os.write.over(fileList, files.map(relPathString).mkString("\n") + "\n")
    val run      = BuildCtx.withFilesystemCheckerDisabled {
      os.call(
        cmd = Seq(csCli, "review", "--file-list", fileList.toString, "--output-format", "json"),
        cwd = BuildCtx.workspaceRoot,
        env = Task.env ++ codeSceneEnv,
        mergeErrIntoOut = false,
        check = false
      )
    }
    val stdout   = run.out.text()
    val stderr   = run.err.text().trim
    if run.exitCode != 0 then
      val details = Seq(stdout.trim, stderr).filter(_.nonEmpty).mkString("\n")
      Result.Failure(s"CodeScene review exited ${run.exitCode}\n$details")
    else
      os.write.over(report, stdout)
      Result.create(CodeSceneReview.parse(stdout))

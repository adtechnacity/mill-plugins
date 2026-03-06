package atn.mill

import mill.*
import mill.api.{DefaultTaskModule, Evaluator, PathRef, SelectMode, Task}

/**
 * Aggregation trait for stryker4s reports across all modules.
 *
 * Add to your root build as:
 * {{{
 * object stryker4s extends DefaultTaskModule with Stryker4sReport {
 *   override def defaultTask(): String = "jsonReportAll"
 * }
 * }}}
 */
trait Stryker4sReport extends Module:

  /** Aggregate all JSON mutation reports into a single directory. */
  def jsonReportAll(evaluator: Evaluator) = Task.Command(exclusive = true) {
    val reports = evaluator
      .resolveTasks(Seq("__.strykerJsonReport"), SelectMode.Multi)
      .toEither
      .fold(
        e => throw new InternalError(s"Could not resolve strykerJsonReport: $e"),
        tasks =>
          evaluator
            .execute(tasks.asInstanceOf[Seq[Task[Any]]])
            .executionResults
            .results
            .map(_.get.value.asInstanceOf[PathRef])
      )

    val dest = Task.dest / "stryker4s-reports"
    os.makeDir.all(dest)
    reports.foreach { ref =>
      val src = ref.path
      if os.exists(src) && os.isDir(src) then os.list(src).foreach(f => os.copy.over(f, dest / f.last))
      else if os.exists(src) && src.ext == "json" then os.copy.over(src, dest / src.last)
    }
    PathRef(dest)
  }

  /** Aggregate all HTML mutation reports into a single directory. */
  def htmlReportAll(evaluator: Evaluator) = Task.Command(exclusive = true) {
    val reports = evaluator
      .resolveTasks(Seq("__.strykerHtmlReport"), SelectMode.Multi)
      .toEither
      .fold(
        e => throw new InternalError(s"Could not resolve strykerHtmlReport: $e"),
        tasks =>
          evaluator
            .execute(tasks.asInstanceOf[Seq[Task[Any]]])
            .executionResults
            .results
            .map(_.get.value.asInstanceOf[PathRef])
      )

    val dest = Task.dest / "stryker4s-reports"
    os.makeDir.all(dest)
    reports.foreach { ref =>
      val src = ref.path
      if os.exists(src) && os.isDir(src) then
        val moduleName = src.segments.toSeq.reverse.dropWhile(!_.startsWith("stryker4s")).headOption.getOrElse("unknown")
        val moduleDest = dest / moduleName
        os.makeDir.all(moduleDest)
        os.list(src).foreach(f => os.copy.over(f, moduleDest / f.last))
    }
    PathRef(dest)
  }

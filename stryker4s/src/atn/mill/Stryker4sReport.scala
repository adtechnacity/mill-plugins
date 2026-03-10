package atn.mill

import mill.*
import mill.api.{Evaluator, PathRef, SelectMode, Task}
import os.Path

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
    aggregateReports(evaluator, "__.strykerJsonReport") { (ref, dest, moduleName) =>
      val src = ref.path
      if os.exists(src) then os.copy.over(src, dest / s"$moduleName.json")
    }()
  }

  /** Aggregate all HTML mutation reports into a single directory. */
  def htmlReportAll(evaluator: Evaluator) = Task.Command(exclusive = true) {
    aggregateReports(evaluator, "__.strykerHtmlReport") { (ref, dest, moduleName) =>
      val src = ref.path
      val moduleDest = dest / os.SubPath(moduleName.replace('.', '/'))
      os.makeDir.all(moduleDest)
      if os.exists(src) then os.copy(src / os.up, moduleDest, createFolders = true)
    }()
  }

  private def aggregateReports(evaluator: Evaluator, taskSelector: String)(
      copyReport: (PathRef, Path, String) => Unit
  ): Task[PathRef] = {
    val namedTasks = evaluator
      .resolveTasks(Seq(taskSelector), SelectMode.Separated)
      .get

    val moduleNames = namedTasks.map { t =>
      val full = t.toString // e.g. "example.strykerJsonReport"
      val label = t.asInstanceOf[Task.Named[?]].label
      full.stripSuffix(s".$label")
    }
    val tasks = namedTasks.asInstanceOf[Seq[Task[PathRef]]]

    Task.Anon {
      val reports = Task.sequence(tasks)()
      reports.zip(moduleNames).foreach(copyReport(_, Task.dest, _))
      PathRef(Task.dest)
    }
  }

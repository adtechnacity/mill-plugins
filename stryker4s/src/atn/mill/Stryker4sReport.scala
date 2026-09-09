package atn.mill

import mill.*
import mill.api.{Evaluator, PathRef, SelectMode, Task}
import os.Path

/**
 * Aggregation trait for stryker4s reports across all modules with defaultTask `runAll`
 *
 * Add to your root build as:
 * {{{
 * object stryker4s extends Stryker4sReport
 * }}}
 */
trait Stryker4sReport extends DefaultTaskModule:

  override def defaultTask(): String = "runAll"

  /** Run mutation testing on all discovered Stryker4sModule modules. */
  def runAll(evaluator: Evaluator) = Task.Command(exclusive = true)[Unit] {
    evaluator.evaluate(Seq("__.strykerMutate"))
  }

  /** Aggregate all JSON mutation reports into a single directory. */
  def jsonReportAll(evaluator: Evaluator) = Task.Command(exclusive = true) {
    aggregateReports(evaluator, "__.strykerJsonReport")(copyJsonReport)()
  }

  /** Aggregate all HTML mutation reports into a single directory. */
  def htmlReportAll(evaluator: Evaluator) = Task.Command(exclusive = true) {
    aggregateReports(evaluator, "__.strykerHtmlReport")(copyHtmlReport)()
  }

  // The copy steps are methods rather than lambdas inside the commands: stryker4s wraps a command's whole body in the
  // mutation switch of its `exclusive = true` literal, and when it rolls back a non-compiling mutant nested inside
  // (the `os.exists` -> `os.forall` one) it drops that outer switch's default case with it, leaving the command without
  // its original code (stryker4s-core 0.21.0, MutantInstrumenter.attemptRemoveMutant).

  /** `<dest>/<module>.json`, when the module has a JSON report. */
  private def copyJsonReport(ref: PathRef, dest: Path, moduleName: String): Unit =
    val src = ref.path
    if os.exists(src) then os.copy.over(src, dest / s"$moduleName.json")

  /** The module's newest report dir under `<dest>/<module path>/`, which is created even without an HTML report. */
  private def copyHtmlReport(ref: PathRef, dest: Path, moduleName: String): Unit =
    val src        = ref.path
    val moduleDest = dest / os.SubPath(moduleName.replace('.', '/'))
    os.makeDir.all(moduleDest)
    // Merge into the dir just created: a plain copy onto an existing directory fails with FileAlreadyExists.
    if os.exists(src) then os.copy(src / os.up, moduleDest, mergeFolders = true)

  private def aggregateReports(evaluator: Evaluator, taskSelector: String)(
    copyReport: (PathRef, Path, String) => Unit
  ): Task[PathRef] = {
    val tasks = evaluator
      .resolveTasks(Seq(taskSelector), SelectMode.Separated)
      .get
      .asInstanceOf[List[Task.Named[PathRef]]]

    val moduleNames = tasks.map { t =>
      val full  = t.toString // e.g. "example.strykerJsonReport"
      val label = t.label
      full.stripSuffix(s".$label")
    }

    Task.Anon {
      Task
        .sequence(tasks)()
        .zip(moduleNames)
        .foreach(copyReport(_, Task.dest, _))
      PathRef(Task.dest)
    }
  }

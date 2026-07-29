package atn.mill

import os.Path
import mill.api.Result
import mill.api.daemon.Logger
import mill.constants.Util.isWindows
import mill.api.SelectMode

import scala.util.Try

class GitInstall(
  gitHooksPath: Path,
  logger: Logger,
  preCommitExtraCommands: Seq[String] = Seq.empty,
  prePushExtraCommands: Seq[String] = Seq.empty,
  selectiveSnapshotTasks: Seq[String] = Seq("__.test"),
  selectivePreCommitTasks: Seq[String] = Seq.empty
) {

  val selectiveJson = "out/mill-selective-execution.json"

  val preCommitHookPath     = gitHooksPath / "pre-commit"
  val prePushHookPath       = gitHooksPath / "pre-push"
  val prepareCommitHookPath = gitHooksPath / "prepare-commit-msg"
  val commitHookPath        = gitHooksPath / "commit-msg"

  val perms = Integer.parseInt("755", 8)

  val filePrefix =
    if (isWindows) ""
    else "#!/bin/sh\n"

  val cmd =
    if (isWindows) ".\\mill.bat"
    else "./mill"

  /**
   * Run `selectors` selectively against the snapshot, falling back to a full run (tasks joined with `+`) when no
   * snapshot exists yet. Mirrors the pre-push test gate so format/scalafix only re-check what changed since the last
   * `selective.prepare`.
   */
  def selectiveOrFull(selectors: Seq[String]): String =
    s"""SELECTIVE_JSON="$selectiveJson"
       |if [ -f "$$SELECTIVE_JSON" ]; then
       |  $cmd selective.run ${selectors.mkString(" ")}
       |else
       |  $cmd ${selectors.mkString(" + ")}
       |fi""".stripMargin

  def writePreCommitHook(path: Path) = {
    logger.debug("writing pre-commit hook")
    val extraLines   = preCommitExtraCommands.map(c => s"$c\n").mkString
    // mill-build sources are tiny and outside the selective graph, so always format-check them in full.
    val metaFormat   = s"$cmd --meta-level 1 mill.scalalib.scalafmt.ScalafmtModule/checkFormatAll"
    val projectCheck =
      if (selectivePreCommitTasks.isEmpty) s"$cmd git.preCommit"
      else selectiveOrFull(selectivePreCommitTasks)
    os.write.over(
      path,
      s"""$filePrefix
         |# Abort the commit if a gate or a check fails.
         |set -e
         |$extraLines$metaFormat
         |$projectCheck
         |""".stripMargin,
      perms
    )
    WrotePreCommitHook
  }

  def writePrePushHook(path: Path) = {
    logger.debug("writing pre-push hook")
    // Each gate runs on its own line; `set -e` aborts the push on the first non-zero exit.
    val extraLines = prePushExtraCommands.map(c => s"$c\n").mkString
    os.write.over(
      path,
      s"""$filePrefix
         |# Abort the push (and skip the snapshot update) if a gate or the test run fails.
         |set -e
         |$extraLines# Run only tests affected by changes since last successful push.
         |# Falls back to all tests when no selective snapshot exists (first run).
         |SELECTIVE_JSON="$selectiveJson"
         |if [ -f "$$SELECTIVE_JSON" ]; then
         |  $cmd selective.run __.test
         |else
         |  $cmd git.prePush
         |fi
         |# Update the selective snapshot so the next push (and pre-commit) only re-check what changed.
         |# Snapshot a superset of every selector run via selective.run, since absent inputs count as changed.
         |$cmd selective.prepare ${selectiveSnapshotTasks.mkString(" ")}
         |""".stripMargin,
      perms
    )
    WrotePrePushHook
  }

  def writePrepareCommitMsgHook(path: Path) = {
    logger.debug("writing prepare-commit-message hook")
    os.write.over(
      path,
      s"""$filePrefix
         |if [ -n "$$2" ]; then
         |  $cmd git.prepCommit --file $$1 --source $$2
         |else
         |  $cmd git.prepCommit --file $$1
         |fi
         |""".stripMargin,
      perms
    )
    WrotePrepareCommitMsgHook
  }

  def writeCommitHook(path: Path) = {
    logger.debug("writing commit hook")
    os.write.over(
      path,
      s"""$filePrefix
         |$cmd git.validateCommit --file $$1
         |""".stripMargin,
      perms
    )
    WroteCommitHook
  }

  def writeNext(force: Boolean, path: Path, op: Path => WorkDone)(prev: Result[WorkDone]): Try[Result[WorkDone]] = Try {
    if (os.exists(path) && !force) {
      logger.info(s"$path exists, not touching")
      prev
    } else prev.map(_.and(op(path)))
  }.recover { case e: Exception =>
    Result.Failure(s"$path was not written\n${e.getMessage}")
  }

  def install(force: Boolean): Try[Result[WorkDone]] =
    writeNext(force, preCommitHookPath, writePreCommitHook)(Result.Success(NotAThing))
      .flatMap(writeNext(force, prePushHookPath, writePrePushHook))
      .flatMap(writeNext(force, prepareCommitHookPath, writePrepareCommitMsgHook))
      .flatMap(writeNext(force, commitHookPath, writeCommitHook))

}

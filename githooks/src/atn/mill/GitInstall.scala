package atn.mill

import os.Path
import mill.api.Result
import mill.api.daemon.Logger
import mill.constants.Util.isWindows
import mill.api.SelectMode

import scala.util.Try

class GitInstall(gitHooksPath: Path, logger: Logger, preCommitExtraCommands: Seq[String] = Seq.empty) {

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

  def writePreCommitHook(path: Path) = {
    logger.debug("writing pre-commit hook")
    val extraLines = preCommitExtraCommands.map(c => s"$c && \\\n").mkString
    os.write.over(
      path,
      s"""$filePrefix
         |$extraLines$cmd --meta-level 1 mill.scalalib.scalafmt.ScalafmtModule/checkFormatAll && \\
         |$cmd git.preCommit
         |""".stripMargin,
      perms
    )
    WrotePreCommitHook
  }

  def writePrePushHook(path: Path) = {
    logger.debug("writing pre-push hook")
    os.write.over(
      path,
      s"""$filePrefix
         |# Run only tests affected by changes since last successful push.
         |# Falls back to all tests when no selective snapshot exists (first run).
         |SELECTIVE_JSON="out/mill-selective-execution.json"
         |if [ -f "$$SELECTIVE_JSON" ]; then
         |  $cmd selective.run __.test
         |else
         |  $cmd git.prePush
         |fi
         |# Update selective snapshot so the next push only re-tests what changed.
         |$cmd selective.prepare __.test
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

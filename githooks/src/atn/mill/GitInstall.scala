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
  selectivePreCommitTasks: Seq[String] = Seq.empty,
  signingActive: Boolean = false
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
    val signingBlock =
      if (!signingActive) ""
      else
        s"""|# Check whether this change requires a signed commit before it's made.
            |if [ -n "$$GIT_INDEX_FILE" ]; then
            |  $cmd git.checkSigning --index "$$GIT_INDEX_FILE"
            |else
            |  $cmd git.checkSigning
            |fi
            |""".stripMargin
    os.write.over(
      path,
      s"""$filePrefix
         |# Abort the commit if a gate or a check fails.
         |set -e
         |$extraLines$metaFormat
         |$projectCheck
         |$signingBlock""".stripMargin,
      perms
    )
    WrotePreCommitHook
  }

  def writePrePushHook(path: Path) = {
    logger.debug("writing pre-push hook")
    // Each gate runs on its own line; `set -e` aborts the push on the first non-zero exit.
    val extraLines   = prePushExtraCommands.map(c => s"$c\n").mkString
    val signingBlock =
      if (!signingActive) ""
      else
        s"""|# Verify signing requirements for every commit being pushed (lenient: local config drift never blocks
            |# a push, only the remote gate is authoritative). Reads the ref-update protocol from stdin and redirects
            |# stdin away from every Mill invocation inside the loop, so Mill never swallows the remaining ref lines.
            |while read local_ref local_sha remote_ref remote_sha; do
            |  case "$$remote_ref" in refs/tags/*) continue ;; esac
            |  case "$$local_sha" in 0000000000000000000000000000000000000000) continue ;; esac
            |  case "$$remote_sha" in
            |    0000000000000000000000000000000000000000)
            |      echo "git.verifyRange: new branch push, signing verified server-side only (local check skipped)" >&2
            |      continue ;;
            |  esac
            |  $cmd git.verifyRange "$$remote_sha" "$$local_sha" --lenient < /dev/null
            |done
            |""".stripMargin
    os.write.over(
      path,
      s"""$filePrefix
         |# Abort the push (and skip the snapshot update) if a gate or the test run fails.
         |set -e
         |$signingBlock$extraLines# Run only tests affected by changes since last successful push.
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

  /**
   * `writeNext` silently skips an existing hook file without `--force`, so a keys dir added or removed since the last
   * install would otherwise go unnoticed. Pure predicate (no logging) so it's directly testable; `install` reports
   * whatever it finds via `logger.error`.
   */
  private[mill] def activationDriftMessages(force: Boolean): Seq[String] =
    if (force) Seq.empty
    else
      Seq(preCommitHookPath, prePushHookPath).filter(os.exists).flatMap { path =>
        val content         = os.read(path)
        val hasSigningLines = content.contains("git.checkSigning") || content.contains("git.verifyRange")
        Option.when(hasSigningLines != signingActive)(
          s"$path: signing activation drifted (trusted keys were " +
            s"${if (signingActive) "added" else "removed"} since this hook was last installed) — " +
            "reinstall with --force to pick up the change"
        )
      }

  def install(force: Boolean): Try[Result[WorkDone]] = {
    activationDriftMessages(force).foreach(logger.error)
    writeNext(force, preCommitHookPath, writePreCommitHook)(Result.Success(NotAThing))
      .flatMap(writeNext(force, prePushHookPath, writePrePushHook))
      .flatMap(writeNext(force, prepareCommitHookPath, writePrepareCommitMsgHook))
      .flatMap(writeNext(force, commitHookPath, writeCommitHook))
  }

}

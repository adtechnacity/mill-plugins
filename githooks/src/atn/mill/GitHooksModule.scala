package atn.mill

import mill.*
import mill.api._
import mill.scalalib.scalafmt._

import mainargs.{arg, ArgSig, TokensReader}

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.{Repository, RepositoryBuilder}
import org.eclipse.jgit.revwalk.{RevCommit, RevWalk}

import scala.jdk.CollectionConverters._
import scala.util.Try

trait GitHooksModule extends DefaultTaskModule {

  /** Extra shell commands to run in the pre-commit hook before formatting checks. */
  def preCommitExtraCommands: Seq[String] = Seq.empty

  /** Extra shell commands to run in the pre-push hook before tests; any non-zero exit aborts the push. */
  def prePushExtraCommands: Seq[String] = Seq.empty

  /**
   * Task selectors captured by `selective.prepare` in the pre-push snapshot. Must be a superset of every selector run
   * via `selective.run` in any hook (test gate, [[selectivePreCommitTasks]]), because `selective.run` treats inputs
   * absent from the snapshot as changed — a too-narrow snapshot makes selective checks run on every module.
   */
  def selectiveSnapshotTasks: Seq[String] = Seq("__.test")

  /**
   * Selectors run selectively in the pre-commit hook (with a full `+`-joined fallback when no snapshot exists). Empty
   * (default) keeps the legacy full `git.preCommit` format check. Whatever is set here must be covered by
   * [[selectiveSnapshotTasks]].
   */
  def selectivePreCommitTasks: Seq[String] = Seq.empty

  /** Email domain for co-author enrichment in commit preparation. Empty string disables. */
  def emailDomain: String = ""

  /** Optional regex pattern for Jira-style footer validation (e.g. `"Refs: [A-Z]+-\\d+"`). */
  def commitFooterPattern: Option[String] = None

  /** Ollama server URL for AI-assisted commit message generation. */
  def ollamaUrl: String = "http://localhost:11434"

  /** Ollama model name for commit message generation. */
  def ollamaModel: String = "qwen3:8b"

  /** Conventional commit types accepted by the validator. */
  def conventionalCommitTypes: List[String] = GitValidateCommit.DefaultTypes

  /** Module names to exclude from valid module resolution. */
  def excludedModuleNames: Set[String] = Set("test", "integration")

  /** Suppression-marker patterns for the [[ExceptionComments]] signing condition. */
  def exceptionCommentMarkers: Seq[String] = SigningConditions.DefaultMarkers

  /** Test-case header patterns for the [[TestProtection]] signing condition. */
  def testCasePatterns: Seq[String] = SigningConditions.DefaultCasePatterns

  /** Path globs guarded by the [[ProtectedPaths]] signing condition (trust store, tool configs). */
  def protectedPathGlobs: Seq[String] = SigningConditions.DefaultProtectedGlobs

  /**
   * The pluggable signing-condition set (R1). Override this def directly to supply a fully custom list; override
   * [[exceptionCommentMarkers]], [[testCasePatterns]], or [[protectedPathGlobs]] to adjust the built-ins without
   * hand-building conditions (R7).
   */
  def signingConditions: Seq[SigningCondition] =
    SigningConditions.defaults(
      exceptionCommentMarkers,
      SigningConditions.DefaultTestPathPattern,
      testCasePatterns,
      protectedPathGlobs,
      SigningConditions.DefaultSourcePattern
    )

  /**
   * Directory, relative to the repo root, holding trusted signers' armored public keys. Signing enforcement activates
   * (hooks gain the checkSigning/verifyRange lines) only when this directory contains at least one key file — a default
   * install with no keys is byte-identical to a repo that never adopted signing.
   */
  def trustedKeysDir: String = ".mill-signing/trusted-keys"

  /** [[trustedKeysDir]] resolved against the repo root. */
  private def resolvedTrustedKeysDir(rootDir: os.Path): os.Path = rootDir / os.RelPath(trustedKeysDir)

  def install(
    evaluator: Evaluator,
    @arg(
      name = "force",
      short = 'f',
      doc = "overwrites existing git hooks, even if they already exist"
    ) force: Boolean = false
  ) =
    Task.Command(exclusive = true)[WorkDone] {
      val ev            = EvaluatorProxy(() => evaluator)
      val keysDir       = resolvedTrustedKeysDir(ev.rootModule.moduleDir)
      val signingActive = os.exists(keysDir) && os.isDir(keysDir) && os.list(keysDir).exists(os.isFile)
      new GitInstall(
        ev.rootModule.moduleDir / ".git/hooks",
        ev.baseLogger,
        preCommitExtraCommands,
        prePushExtraCommands,
        selectiveSnapshotTasks,
        selectivePreCommitTasks,
        signingActive
      )
        .install(force) match {
        case scala.util.Success(result) => result
        case scala.util.Failure(e)      => Result.Failure(e.getMessage)
      }
    }

  def preCommit() =
    ScalafmtModule.checkFormatAll()

  def prepCommit(evaluator: Evaluator, file: os.Path, source: String = "commit") =
    Task.Command(exclusive = true)[Unit] {
      val ev      = EvaluatorProxy(() => evaluator)
      val modules = validModules(ev.rootModule)
      val msg     = os.read(file)
      val gpc     = GitRepo.repo.map(
        new GitPrepCommit(_, modules.toList, ev.baseLogger, ollamaUrl, ollamaModel, emailDomain, commitFooterPattern)
      )
      gpc.map(gpc => os.write.over(file, gpc.prep(msg, source)))
    }

  def validateCommit(evaluator: Evaluator, file: os.Path) =
    Task.Command(exclusive = true)[Unit] {
      val ev        = EvaluatorProxy(() => evaluator)
      val modules   = validModules(ev.rootModule)
      val validator = GitRepo.repo.map(new GitValidateCommit(_, conventionalCommitTypes, modules, ev.baseLogger))
      val msg       = os.read(file)
      validator.flatMap(_.validate(msg))
    }

  /**
   * Pre-commit signing intent check (R4): evaluates [[signingConditions]] over the staged diff (read from `index` when
   * given — the hook passes `$GIT_INDEX_FILE` so `git commit -a`/pathspec temporary indexes are honored) and, if any
   * condition fires, requires `commit.gpgsign=true` in the repo-local config. Real signature verification happens at
   * push time; this only checks that the commit about to be made will end up signed. A merge in progress (`MERGE_HEAD`
   * present) is skipped with a notice — a staged diff mid-merge shows the whole merged branch as added, which would
   * false-reject routine merges.
   */
  def checkSigning(
    evaluator: Evaluator,
    @arg(
      name = "index",
      doc = "path to the index file to read staged changes from (the hook passes $GIT_INDEX_FILE)"
    ) index: Option[String] = None
  ) =
    Task.Command(exclusive = true)[Unit] {
      val ev = EvaluatorProxy(() => evaluator)
      GitRepo.repo.flatMap { repo =>
        if (GitHooksModule.isMergeInProgress(repo))
          ev.baseLogger.info("git.checkSigning: merge in progress, deferring signing check to pre-push")
        GitHooksModule.checkSigningOutcome(repo, index.map(os.Path(_)), signingConditions) match {
          case Right(()) => Result.Success(())
          case Left(msg) => Result.Failure(s"git.checkSigning: $msg")
        }
      }
    }

  /**
   * Verifies every commit in `oldRef..newRef` against [[signingConditions]] and the trusted keys in [[trustedKeysDir]]
   * (R5, R10). `lenient` (the pre-push hook's mode) passes with a notice instead of failing when signing isn't
   * configured at all — local config drift never blocks a push; CI and manual invocation use the strict default, where
   * a missing or misconfigured trust store is a failure.
   */
  def verifyRange(
    evaluator: Evaluator,
    oldRef: String,
    newRef: String,
    @arg(
      name = "lenient",
      doc = "pass with a notice instead of failing when signing isn't configured (pre-push hook mode)"
    ) lenient: Boolean = false
  ) =
    Task.Command(exclusive = true)[Unit] {
      val ev = EvaluatorProxy(() => evaluator)
      GitRepo.repo.flatMap { repo =>
        Try {
          val walk = new RevWalk(repo)
          try {
            val oldId =
              Option(repo.resolve(oldRef)).getOrElse(throw new IllegalArgumentException(s"cannot resolve '$oldRef'"))
            val newId =
              Option(repo.resolve(newRef)).getOrElse(throw new IllegalArgumentException(s"cannot resolve '$newRef'"))
            walk.markStart(walk.parseCommit(newId))
            walk.markUninteresting(walk.parseCommit(oldId))
            Iterator.continually(walk.next()).takeWhile(_ != null).toVector
          } finally walk.close()
        }.toEither.left
          .map(e => s"git.verifyRange: ${e.getMessage}")
          .flatMap { commits =>
            val keysDir  = resolvedTrustedKeysDir(ev.rootModule.moduleDir)
            val failures = commits.flatMap(GitHooksModule.commitFailure(repo, _, signingConditions, keysDir, lenient))
            Either.cond(failures.isEmpty, (), failures.mkString("\n"))
          } match {
          case Right(()) => Result.Success(())
          case Left(msg) => Result.Failure(msg)
        }
      }
    }

  /** Task selectors resolved by prePush to run tests before pushing. */
  def prePushTasks: Seq[String] = Seq("__.test")

  def prePush(evaluator: Evaluator) =
    Task.Command(exclusive = true)[Unit] {
      val ev = EvaluatorProxy(() => evaluator)
      ev.resolveTasks(prePushTasks, SelectMode.Multi) match {
        case f: Result.Failure     => f
        case Result.Success(tasks) =>
          ev.execute(tasks.asInstanceOf[Seq[Task[Any]]]).values match {
            case f: Result.Failure => Result.Failure(s"Tests failed: ${f.error}")
            case _                 => ()
          }
      }
    }

  def validModules(rootModule: Module): Set[String] = {
    def more(module: Module): Seq[String] =
      module.moduleDirectChildren
        .filter(_.moduleSegments.parts.nonEmpty)
        .map(_.moduleSegments.last.value)
        .filter(m => m.head == m.head.toLower)
        .filterNot(excludedModuleNames.contains) ++
        module.moduleDirectChildren.flatMap(more)
    more(rootModule)
      .appended("mill-build")
      .toSet
  }

  /** Delegate to core GitRepo for head branch. */
  def headBranch() = GitRepo.headBranch()

  /** Delegate to core GitRepo for head SHA. */
  def headSHA() = GitRepo.headSHA()

  /** Delegate to core GitRepo for head tag. */
  def headTag() = GitRepo.headTag()

}

object GitHooksModule extends ExternalModule with GitHooksModule {
  override def defaultTask(): String = "install"

  lazy val millDiscover: Discover = Discover[this.type]

  /** A merge in progress (`MERGE_HEAD` present) means a staged diff shows the whole merged branch as added. */
  private[mill] def isMergeInProgress(repo: Repository): Boolean =
    os.exists(os.Path(repo.getDirectory) / "MERGE_HEAD")

  /**
   * Core [[GitHooksModule.checkSigning]] logic, repo- and condition-parameterized so it's testable without a live Mill
   * evaluator. `Right(())` means the commit-in-progress may proceed (nothing triggered, or triggered and
   * `commit.gpgsign` is set); `Left` carries the rejection message.
   */
  private[mill] def checkSigningOutcome(
    repo: Repository,
    index: Option[os.Path],
    conditions: Seq[SigningCondition]
  ): Either[String, Unit] =
    if (isMergeInProgress(repo)) Right(())
    else
      GitDiff.stagedChanges(repo, index).flatMap { changes =>
        val reasons = SigningConditions.evaluate(conditions, changes)
        if (reasons.isEmpty || repo.getConfig.getBoolean("commit", "gpgsign", false)) Right(())
        else
          Left(
            "this change requires a signed commit —\n" +
              reasons.map(r => s"  [${r.condition}] ${r.detail}").mkString("\n") +
              "\nenable signing for this commit: `git config commit.gpgsign true` (or commit with `-S`)"
          )
      }

  /**
   * Core [[GitHooksModule.verifyRange]] per-commit logic, repo- and condition-parameterized for the same reason as
   * [[checkSigningOutcome]]. `None` means the commit passes.
   */
  private[mill] def commitFailure(
    repo: Repository,
    commit: RevCommit,
    conditions: Seq[SigningCondition],
    keysDir: os.Path,
    lenient: Boolean
  ): Option[String] = {
    val changes =
      if (commit.getParentCount >= 2) GitDiff.mergeCombinedChanges(repo, commit)
      else GitDiff.commitChanges(repo, commit)
    val sha     = commit.getName.take(8)
    changes match {
      case Left(err) => Some(s"$sha: $err")
      case Right(cs) =>
        val reasons = SigningConditions.evaluate(conditions, cs)
        if (reasons.isEmpty) None
        else
          TrustedKeys.fromDirectory(keysDir) match {
            case Left(err) if lenient && err.startsWith("trusted-keys configuration error") => None
            case Left(err)                                                                  =>
              Some(s"$sha: signing configuration error: $err")
            case Right(trusted)                                                             =>
              SigningVerify.verify(commit, trusted) match {
                case SigningVerdict.Trusted(_)        => None
                case SigningVerdict.Unsigned          =>
                  Some(s"$sha: unsigned but signing is required (${reasons.map(_.condition).distinct.mkString(", ")})")
                case SigningVerdict.Unverifiable(fmt) =>
                  Some(s"$sha: signature format '$fmt' is not verifiable (v1 supports OpenPGP only)")
                case SigningVerdict.Invalid(reason)   => Some(s"$sha: invalid signature ($reason)")
                case SigningVerdict.Untrusted(fp)     =>
                  Some(s"$sha: signed by an untrusted key ($fp) — a maintainer must re-sign or add this key to the trust root")
                case SigningVerdict.Revoked(fp)       => Some(s"$sha: signed by a revoked key ($fp)")
                case SigningVerdict.Expired(fp)       => Some(s"$sha: signed by a key expired at signing time ($fp)")
              }
          }
    }
  }
}

package atn.mill

import mainargs.{arg, main, ParserForMethods}

import org.eclipse.jgit.lib.{Constants, ObjectId, Repository, RepositoryBuilder}
import org.eclipse.jgit.revwalk.{RevCommit, RevWalk}

import scala.jdk.CollectionConverters._
import scala.util.Try

/**
 * Thin, Mill-API-free CLI wrapping [[GitDiff]]/[[SigningConditions]]/[[SigningVerify]]/[[TrustedKeys]] for a
 * self-hosted server's `pre-receive` hook. Launched via `cs launch com.adtechnacity:mill-githooks_mill1_3:<version>
 * --main-class atn.mill.VerifyMain` — every type this touches was built without a `mill.*` import specifically so the
 * published JAR runs standalone, with no Mill API on the classpath.
 *
 * Enforces the built-in [[SigningConditions.defaults]] with default configuration only — a pre-receive hook cannot
 * evaluate a pushing repo's `build.mill`, so custom conditions are CI's job, not the server's. Trusted keys are always
 * read from `--trust-root-ref`'s tree, never from the pushed commits, including for a brand-new branch whose only
 * commit introduces its own key.
 */
object VerifyMain {

  private[mill] val ZeroId: String = "0" * 40

  /** DoS guards on untrusted push volume: exceeding either fails the whole ref update closed, never silently. */
  final private[mill] case class Ceilings(maxCommits: Int, maxChangedLinesPerCommit: Int)

  private[mill] object Ceilings {
    val Default: Ceilings = Ceilings(maxCommits = 500, maxChangedLinesPerCommit = 20000)
  }

  /** One `old_sha new_sha ref_name` line of the real git pre-receive stdin protocol. */
  final private[mill] case class RefUpdate(oldId: String, newId: String, ref: String)

  private val HexSha = "^[0-9a-f]{40}$".r

  private[mill] def parseUpdates(lines: Iterator[String]): Either[String, Vector[RefUpdate]] =
    lines.zipWithIndex.foldLeft[Either[String, Vector[RefUpdate]]](Right(Vector.empty)) { case (acc, (line, i)) =>
      acc.flatMap { xs =>
        line.trim.split(' ') match {
          case Array(o, n, r) if HexSha.matches(o) && HexSha.matches(n) && r.nonEmpty => Right(xs :+ RefUpdate(o, n, r))
          case _                                                                      => Left(s"malformed pre-receive input at line ${i + 1}: '$line'")
        }
      }
    }

  private[mill] def openRepo(gitDir: os.Path): Repository =
    new RepositoryBuilder().setGitDir(gitDir.toIO).readEnvironment().build()

  /** The trust root defaults to the repo's own current `HEAD` symref, e.g. `refs/heads/main`. */
  private[mill] def defaultTrustRootRef(repo: Repository): String =
    Option(repo.getFullBranch).getOrElse(Constants.HEAD)

  private def existingRefTips(repo: Repository): Vector[ObjectId] =
    repo.getRefDatabase.getRefsByPrefix(Constants.R_REFS).asScala.toVector.flatMap(r => Option(r.getObjectId))

  /**
   * Commits a ref update needs verified: `old..new` for an existing ref; for a new branch (`old` all-zero), every
   * commit not already reachable from some other existing ref — a branch always forks off existing history, so this is
   * exactly the set of commits this push actually introduces. A deleted ref (`new` all-zero) needs nothing.
   */
  private[mill] def commitsToVerify(
    repo: Repository,
    update: RefUpdate,
    ceilings: Ceilings
  ): Either[String, Vector[RevCommit]] =
    if (update.newId == ZeroId) Right(Vector.empty)
    else
      Try {
        val walk = new RevWalk(repo)
        try {
          walk.markStart(walk.parseCommit(ObjectId.fromString(update.newId)))
          if (update.oldId != ZeroId) walk.markUninteresting(walk.parseCommit(ObjectId.fromString(update.oldId)))
          else existingRefTips(repo).foreach(id => Try(walk.parseCommit(id)).foreach(walk.markUninteresting))
          val out   = Vector.newBuilder[RevCommit]
          var count = 0
          var next  = walk.next()
          while (next != null) {
            count += 1
            if (count > ceilings.maxCommits)
              throw new IllegalStateException(s"range exceeds the ${ceilings.maxCommits}-commit resource ceiling")
            out += next
            next = walk.next()
          }
          out.result()
        } finally walk.close()
      }.toEither.left.map(e => s"${update.ref}: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}")

  private def changesOf(repo: Repository, commit: RevCommit): Either[String, GitDiff.ChangeSet] =
    if (commit.getParentCount >= 2) GitDiff.mergeCombinedChanges(repo, commit) else GitDiff.commitChanges(repo, commit)

  private def changedLines(cs: GitDiff.ChangeSet): Int = cs.iterator.map(f => f.added.size + f.removed.size).sum

  private def verdictFailure(
    commit: RevCommit,
    reasons: Vector[SigningReason],
    trusted: TrustedKeys
  ): Option[String] = {
    val sha = commit.getName.take(8)
    SigningVerify.verify(commit, trusted) match {
      case SigningVerdict.Trusted(_)        => None
      case SigningVerdict.Unsigned          =>
        Some(s"$sha: unsigned but signing is required (${reasons.map(_.condition).distinct.mkString(", ")})")
      case SigningVerdict.Unverifiable(fmt) =>
        Some(s"$sha: signature format '$fmt' is not verifiable (the server enforces OpenPGP only)")
      case SigningVerdict.Invalid(reason)   => Some(s"$sha: invalid signature ($reason)")
      case SigningVerdict.Untrusted(fp)     =>
        Some(s"$sha: signed by an untrusted key ($fp) — a maintainer must re-sign or add this key to the trust root")
      case SigningVerdict.Revoked(fp)       => Some(s"$sha: signed by a revoked key ($fp)")
      case SigningVerdict.Expired(fp)       => Some(s"$sha: signed by a key expired at signing time ($fp)")
    }
  }

  /**
   * Verify one ref update. Trusted keys are loaded at most once, and only when some commit in range actually triggers a
   * condition — an unprotected-only push never touches the trust root, so repos with no keys dir at all (bootstrap, or
   * non-adopters on a shared server) are completely unaffected by this hook being installed.
   */
  private[mill] def verifyUpdate(
    repo: Repository,
    update: RefUpdate,
    trustRootRef: String,
    trustedKeysPath: String,
    ceilings: Ceilings
  ): Vector[String] =
    commitsToVerify(repo, update, ceilings) match {
      case Left(err)      => Vector(err)
      case Right(commits) =>
        val triggered =
          commits.foldLeft[Either[String, Vector[(RevCommit, Vector[SigningReason])]]](Right(Vector.empty)) { (acc, c) =>
            acc.flatMap { xs =>
              changesOf(repo, c).left
                .map(e => s"${c.getName.take(8)}: $e")
                .flatMap { cs =>
                  if (changedLines(cs) > ceilings.maxChangedLinesPerCommit)
                    Left(s"${c.getName.take(8)}: diff exceeds the ${ceilings.maxChangedLinesPerCommit}-line resource ceiling")
                  else {
                    val reasons = SigningConditions.evaluate(SigningConditions.defaults, cs)
                    Right(if (reasons.isEmpty) xs else xs :+ c -> reasons)
                  }
                }
            }
          }
        triggered match {
          case Left(err)                     => Vector(err)
          case Right(fired) if fired.isEmpty => Vector.empty
          case Right(fired)                  =>
            TrustedKeys.fromRef(repo, trustRootRef, trustedKeysPath) match {
              case Left(err)      => Vector(s"${update.ref}: signing configuration error: $err")
              case Right(trusted) => fired.flatMap { case (c, reasons) => verdictFailure(c, reasons, trusted) }
            }
        }
    }

  /**
   * Verify every ref update read from `stdinLines` against a repo at `gitDir`. Returns the process exit code and any
   * failure lines (empty on success). Never touches the working tree or checks out anything — `gitDir` is the bare
   * repo's directory (a pre-receive hook's CWD), or, in tests, any repo's `.git` directory — this class only reads
   * objects and refs, so it doesn't care whether the repo is bare.
   */
  private[mill] def run(
    gitDir: os.Path,
    trustRootRefOverride: Option[String],
    trustedKeysPath: String,
    stdinLines: Iterator[String],
    ceilings: Ceilings = Ceilings.Default
  ): (Int, Vector[String]) =
    Try(openRepo(gitDir)) match {
      case scala.util.Failure(e)    => (1, Vector(s"cannot open repository at $gitDir: ${e.getMessage}"))
      case scala.util.Success(repo) =>
        try {
          val trustRootRef = trustRootRefOverride.getOrElse(defaultTrustRootRef(repo))
          parseUpdates(stdinLines) match {
            case Left(err)      => (1, Vector(err))
            case Right(updates) =>
              val failures = updates.flatMap(verifyUpdate(repo, _, trustRootRef, trustedKeysPath, ceilings))
              if (failures.isEmpty) (0, Vector.empty) else (1, failures)
          }
        } finally repo.close()
    }

  @main
  def cli(
    @arg(doc = "the repository's git directory (a pre-receive hook's CWD, for a bare repo)")
    repo: String = ".",
    @arg(doc = "ref whose tree holds the trusted-keys directory; defaults to the repo's current HEAD symref")
    trustRootRef: Option[String] = None,
    @arg(doc = "path, within the trust-root ref's tree, to the trusted-keys directory")
    trustedKeysPath: String = ".mill-signing/trusted-keys"
  ): Unit = {
    val (code, failures) = run(os.Path(repo, os.pwd), trustRootRef, trustedKeysPath, scala.io.Source.stdin.getLines())
    failures.foreach(System.err.println)
    sys.exit(code)
  }

  def main(args: Array[String]): Unit = ParserForMethods(this).runOrExit(args.toIndexedSeq): Unit
}

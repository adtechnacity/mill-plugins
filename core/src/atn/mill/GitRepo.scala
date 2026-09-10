package atn.mill

import mill.api.daemon.Result

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.{Repository, RepositoryBuilder}

/** Standalone git repository utilities extracted for cross-plugin use. */
object GitRepo:

  lazy val repo = Result.create(new RepositoryBuilder().findGitDir().build())

  def headBranch() = branchOf(repo)

  def headSHA() = shaOf(repo)

  def headTag() = tagOf(repo)

  // The three accessors above read `repo`, which is bound once to the process working directory; these take the
  // repository explicitly so that every path (unborn HEAD, tags, describe) is reachable from a test.

  /** [[headBranch]] of an explicitly opened repository. */
  private[mill] def branchOf(repo: Result[Repository]): Result[String] = repo.map(_.getBranch())

  /** [[headSHA]] of an explicitly opened repository. */
  private[mill] def shaOf(repo: Result[Repository]): Result[String] = repo.flatMap: r =>
    Option(r.resolve("HEAD")) match
      case Some(id) => Result.Success(id.name)
      case None     => Result.Failure("HEAD cannot be resolved (no commits?)")

  /** [[headTag]] of an explicitly opened repository. */
  private[mill] def tagOf(repo: Result[Repository]): Result[String] =
    repo.flatMap: r =>
      Result.create:
        new Git(r)
          .describe()
          .setAlways(true)
          .setTags(true)
          .call()

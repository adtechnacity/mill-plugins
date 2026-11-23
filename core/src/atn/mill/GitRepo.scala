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

  /** Opens the git repository containing `dir` (walking parent directories). */
  def open(dir: os.Path): Result[Repository] =
    val builder = new RepositoryBuilder().findGitDir(dir.toIO)
    Option(builder.getGitDir) match
      case Some(_) => Result.Success(builder.setMustExist(true).build())
      case None    => Result.Failure(s"No git repository found at or above $dir")

  /** URL of the given git remote (defaults to `"origin"`). */
  def remoteUrl(remote: String = "origin"): Result[String] = remoteUrlOf(repo, remote)

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

  /** [[remoteUrl]] of an explicitly opened repository. */
  def remoteUrlOf(repo: Result[Repository], remote: String = "origin"): Result[String] =
    repo.flatMap: r =>
      Option(r.getConfig.getString("remote", remote, "url")).filter(_.nonEmpty) match
        case Some(url) => Result.Success(url)
        case None      => Result.Failure(s"Git remote '$remote' URL is not configured")

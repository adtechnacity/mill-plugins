package atn.mill

import mill.api.daemon.Result

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.RepositoryBuilder

/** Standalone git repository utilities extracted for cross-plugin use. */
object GitRepo:

  lazy val repo = Result.create(new RepositoryBuilder().findGitDir().build())

  def headBranch() = repo.map(_.getBranch())

  def headSHA() = repo.flatMap: r =>
    Option(r.resolve("HEAD")) match
      case Some(id) => Result.Success(id.name)
      case None     => Result.Failure("HEAD cannot be resolved (no commits?)")

  def headTag() =
    repo.flatMap: r =>
      Result.create:
        new Git(r)
          .describe()
          .setAlways(true)
          .setTags(true)
          .call()

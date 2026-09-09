package atn.mill

import utest.*

import mill.api.daemon.Result

import org.eclipse.jgit.api.errors.RefNotFoundException
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit

import scala.util.Try

/**
 * Drives [[GitRepo]] over throwaway jgit repositories. `GitRepo.repo` itself is bound to the working directory of the
 * test process, which sits inside this very checkout, so the public accessors are checked against it as well.
 */
object GitRepoTest extends TestSuite:

  /** A new repository on branch `main` in a temp dir with `commits` empty commits (none leaves HEAD unborn). */
  private def repository(commits: Int): Git =
    val git = Git.init().setDirectory(os.temp.dir().toIO).setInitialBranch("main").call()
    (1 to commits).foreach(n => commit(git, s"commit $n"))
    git

  /** An empty, unsigned commit with a fixed identity so nothing depends on the machine's git configuration. */
  private def commit(git: Git, message: String): RevCommit =
    git
      .commit()
      .setAllowEmpty(true)
      .setSign(false)
      .setMessage(message)
      .setAuthor("Test", "test@example.com")
      .setCommitter("Test", "test@example.com")
      .call()

  private def lightweightTag(git: Git, name: String): Unit =
    git.tag().setName(name).setAnnotated(false).call()

  private def opened(git: Git): Result[Repository] = Result.Success(git.getRepository)

  private def head(git: Git) = git.getRepository.resolve("HEAD")

  private def value(result: Result[String]): String = result match
    case Result.Success(v) => v
    case failure           => throw new java.lang.AssertionError(s"expected a success, got $failure")

  private def error(result: Result[String]): String = result match
    case f: Result.Failure => f.error
    case success           => throw new java.lang.AssertionError(s"expected a failure, got $success")

  val tests = Tests:

    test("branchOf - the checked-out branch") {
      assert(value(GitRepo.branchOf(opened(repository(1)))) == "main")
    }

    test("shaOf - the full object id of HEAD") {
      val git = repository(2)
      assert(value(GitRepo.shaOf(opened(git))) == head(git).name)
      assert(head(git).name.matches("[0-9a-f]{40}"))
    }

    test("shaOf - an unborn HEAD is a failure, not an exception") {
      assert(error(GitRepo.shaOf(opened(repository(0)))).contains("HEAD cannot be resolved"))
    }

    test("tagOf - a lightweight tag on HEAD is reported by name") {
      val git = repository(1)
      lightweightTag(git, "v1.2.3")
      assert(value(GitRepo.tagOf(opened(git))) == "v1.2.3")
    }

    test("tagOf - commits past the tag are described relative to it") {
      val git  = repository(1)
      lightweightTag(git, "v1.2.3")
      val next = commit(git, "after the tag")
      assert(value(GitRepo.tagOf(opened(git))) == s"v1.2.3-1-g${next.abbreviate(7).name}")
    }

    test("tagOf - without any tag HEAD is described by its abbreviated id") {
      val git = repository(1)
      assert(value(GitRepo.tagOf(opened(git))) == head(git).abbreviate(7).name)
    }

    test("tagOf - an unborn HEAD surfaces jgit's exception (Result.create does not wrap it)") {
      val thrown = Try(GitRepo.tagOf(opened(repository(0)))).failed.get
      assert(thrown.isInstanceOf[RefNotFoundException])
      assert(thrown.getMessage.contains("HEAD"))
    }

    test("a repository that could not be opened is passed through as that failure") {
      val unopened: Result[Repository] = Result.Failure("no git directory")
      assert(error(GitRepo.branchOf(unopened)) == "no git directory")
      assert(error(GitRepo.shaOf(unopened)) == "no git directory")
      assert(error(GitRepo.tagOf(unopened)) == "no git directory")
    }

    test("repo - the working directory's repository backs the public accessors") {
      assert(GitRepo.repo.map(_.getDirectory.isDirectory) == Result.Success(true))
      assert(value(GitRepo.headSHA()).matches("[0-9a-f]{40}"))
      assert(value(GitRepo.headBranch()).nonEmpty)
      assert(value(GitRepo.headTag()).nonEmpty)
    }

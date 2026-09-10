package atn.mill

import org.eclipse.jgit.api.Git

/** Throwaway git repositories for the hook tests: every call starts from a fresh temporary directory. */
object GitFixtures:

  /** An initialised repository without commits, in a fresh temporary directory. */
  def emptyRepo(): Git = Git.init().setDirectory(os.temp.dir().toIO).call()

  /** The working tree of `git`. */
  def workTree(git: Git): os.Path = os.Path(git.getRepository.getWorkTree)

  /** Writes `content` to `rel` under the working tree and stages it. */
  def stage(git: Git, rel: String, content: String): Unit =
    os.write.over(workTree(git) / os.RelPath(rel), content, createFolders = true)
    git.add().addFilepattern(rel).call()

  /** Commits what is staged under a fixed identity, unsigned, so the developer's git config cannot interfere. */
  def commit(git: Git, message: String): Unit =
    git
      .commit()
      .setMessage(message)
      .setAuthor("Hooks Test", "hooks@example.com")
      .setCommitter("Hooks Test", "hooks@example.com")
      .setSign(false)
      .call()

  /** A repository whose HEAD is one commit of `README`: the shape `gen` sees in a real checkout. */
  def repoWithCommit(): Git =
    val git = emptyRepo()
    stage(git, "README", "hello\n")
    commit(git, "chore: initial")
    git

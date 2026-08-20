package atn.mill

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.{CommitBuilder, Config, Constants, FileMode, ObjectId, PersonIdent, TreeFormatter}
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.util.{FS, SystemReader}

/**
 * Shared JGit fixture-repo helpers. Every fixture runs hermetic: a [[SystemReader]] delegate hides the host's
 * user/system git config, so machine-level settings (a global `commit.gpgsign`, signing keys, hooks) can never leak
 * into test behavior.
 */
object GitFixtures {

  val ident = new PersonIdent("Test Fixture", "fixture@example.com")

  private def emptyConfig(dir: os.Path, name: String) =
    new FileBasedConfig(null, (dir / name).toIO, FS.DETECTED)

  /** Run `body` with user/system/jgit git config replaced by empty configs. */
  def hermetic[A](body: => A): A = {
    val prev = SystemReader.getInstance()
    val tmp  = os.temp.dir(prefix = "git-fixture-cfg")
    SystemReader.setInstance(new SystemReader.Delegate(prev) {
      override def openUserConfig(parent: Config, fs: FS): FileBasedConfig   = emptyConfig(tmp, "user.gitconfig")
      override def openSystemConfig(parent: Config, fs: FS): FileBasedConfig = emptyConfig(tmp, "system.gitconfig")
      override def openJGitConfig(parent: Config, fs: FS): FileBasedConfig   = emptyConfig(tmp, "jgit.gitconfig")
    })
    try body
    finally SystemReader.setInstance(prev)
  }

  /** A fresh hermetic repo on branch `main` in a temp dir. */
  def withRepo[A](body: (os.Path, Git) => A): A = hermetic {
    val dir = os.temp.dir(prefix = "git-fixture")
    val git = Git.init().setDirectory(dir.toIO).setInitialBranch("main").call()
    try body(dir, git)
    finally git.close()
  }

  def commit(git: Git, msg: String): RevCommit =
    git.commit().setMessage(msg).setSign(false).setAuthor(ident).setCommitter(ident).call()

  def commitFile(git: Git, dir: os.Path, path: String, content: String, msg: String): RevCommit = {
    os.write.over(dir / os.SubPath(path), content, createFolders = true)
    git.add().addFilepattern(path).call()
    commit(git, msg)
  }

  /**
   * Build a commit whose tree is exactly `files` (flat names only), with arbitrary parents — the way to construct
   * precise merge shapes without running a merge. Returns the commit id; no ref is updated.
   */
  def rawCommit(git: Git, files: Map[String, String], parents: Seq[ObjectId], msg: String): ObjectId = {
    val repo = git.getRepository
    val ins  = repo.newObjectInserter()
    try {
      val tf  = new TreeFormatter()
      files.toSeq.sortBy(_._1).foreach { case (name, content) =>
        val blob = ins.insert(Constants.OBJ_BLOB, content.getBytes("UTF-8"))
        tf.append(name, FileMode.REGULAR_FILE, blob)
      }
      val cb  = new CommitBuilder()
      cb.setTreeId(ins.insert(tf))
      cb.setParentIds(parents*)
      cb.setAuthor(ident)
      cb.setCommitter(ident)
      cb.setMessage(msg)
      val cid = ins.insert(cb)
      ins.flush()
      cid
    } finally ins.close()
  }
}

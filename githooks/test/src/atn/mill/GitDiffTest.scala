package atn.mill

import utest._

import org.eclipse.jgit.api.ResetCommand.ResetType
import org.eclipse.jgit.util.SystemReader

object GitDiffTest extends TestSuite {

  import GitFixtures._

  val tests = Tests {

    test("stagedChanges reports added and removed lines with numbers") {
      withRepo { (dir, git) =>
        commitFile(git, dir, "a.txt", "one\ntwo\nthree\n", "base")
        os.write.over(dir / "a.txt", "one\nTWO\nthree\nfour\n")
        git.add().addFilepattern("a.txt").call()
        val Right(changes) = GitDiff.stagedChanges(git.getRepository): @unchecked
        val f              = changes.find(_.path == "a.txt").get
        assert(f.added.map(_.text).contains("TWO"))
        assert(f.added.map(_.text).contains("four"))
        assert(f.removed.map(_.text) == Vector("two"))
        assert(f.removed.head.number == 2)
        assert(!f.binary)
      }
    }

    test("stagedChanges honors an explicit index file distinct from the repo index") {
      withRepo { (dir, git) =>
        commitFile(git, dir, "a.txt", "clean\n", "base")
        os.write.over(dir / "a.txt", "clean\nmarker\n")
        git.add().addFilepattern("a.txt").call()
        val tempIndex      = os.temp(prefix = "alt-index")
        os.copy.over(dir / ".git" / "index", tempIndex)
        git.reset().setMode(ResetType.MIXED).call()
        val Right(viaRepo) = GitDiff.stagedChanges(git.getRepository): @unchecked
        val Right(viaTemp) = GitDiff.stagedChanges(git.getRepository, Some(tempIndex)): @unchecked
        assert(viaRepo.isEmpty)
        assert(viaTemp.exists(_.added.exists(_.text == "marker")))
      }
    }

    test("commitChanges diffs against the first parent") {
      withRepo { (dir, git) =>
        commitFile(git, dir, "a.txt", "one\n", "base")
        val second         = commitFile(git, dir, "a.txt", "one\nnew line\n", "second")
        val Right(changes) = GitDiff.commitChanges(git.getRepository, second): @unchecked
        val f              = changes.find(_.path == "a.txt").get
        assert(f.added.map(_.text) == Vector("new line"))
        assert(f.removed.isEmpty)
      }
    }

    test("root commit diffs against the empty tree") {
      withRepo { (dir, git) =>
        val root           = commitFile(git, dir, "a.txt", "hello\n", "root")
        val Right(changes) = GitDiff.commitChanges(git.getRepository, root): @unchecked
        assert(changes.exists(f => f.path == "a.txt" && f.added.map(_.text) == Vector("hello")))
      }
    }

    test("rename-only reports a rename, not add plus delete") {
      withRepo { (dir, git) =>
        val content        = (1 to 30).map(i => s"stable line $i").mkString("", "\n", "\n")
        commitFile(git, dir, "old.txt", content, "base")
        os.remove(dir / "old.txt")
        os.write(dir / "renamed.txt", content)
        git.add().addFilepattern(".").call()
        git.rm().addFilepattern("old.txt").setCached(true).call()
        val moved          = commit(git, "rename")
        val Right(changes) = GitDiff.commitChanges(git.getRepository, moved): @unchecked
        val f              = changes.find(_.newPath.contains("renamed.txt")).get
        assert(f.oldPath.contains("old.txt"))
        assert(f.added.isEmpty && f.removed.isEmpty)
        assert(!changes.exists(_.path == "old.txt"))
      }
    }

    test("binary files are flagged with no lines") {
      withRepo { (dir, git) =>
        commitFile(git, dir, "a.bin", "text\n", "base")
        os.write.over(dir / "a.bin", Array[Byte](0, 1, 2, 3, 0, 5))
        git.add().addFilepattern("a.bin").call()
        val Right(changes) = GitDiff.stagedChanges(git.getRepository): @unchecked
        val f              = changes.find(_.path == "a.bin").get
        assert(f.binary)
        assert(f.added.isEmpty && f.removed.isEmpty)
      }
    }

    test("gitattributes -diff does not hide text hunks") {
      withRepo { (dir, git) =>
        commitFile(git, dir, ".gitattributes", "*.txt -diff\n", "attrs")
        commitFile(git, dir, "a.txt", "one\n", "base")
        os.write.over(dir / "a.txt", "one\nNOSONAR\n")
        git.add().addFilepattern("a.txt").call()
        val Right(changes) = GitDiff.stagedChanges(git.getRepository): @unchecked
        val f              = changes.find(_.path == "a.txt").get
        assert(f.added.map(_.text) == Vector("NOSONAR"))
      }
    }

    test("empty staging area yields an empty change-set") {
      withRepo { (dir, git) =>
        commitFile(git, dir, "a.txt", "one\n", "base")
        val Right(changes) = GitDiff.stagedChanges(git.getRepository): @unchecked
        assert(changes.isEmpty)
      }
    }

    test("staged diff with no HEAD reports additions against the empty tree") {
      withRepo { (dir, git) =>
        os.write(dir / "first.txt", "brand new\n")
        git.add().addFilepattern("first.txt").call()
        val Right(changes) = GitDiff.stagedChanges(git.getRepository): @unchecked
        assert(changes.exists(f => f.path == "first.txt" && f.added.map(_.text) == Vector("brand new")))
      }
    }

    test("merge combined") {
      test("clean merge of divergent branches is empty") {
        withRepo { (_, git) =>
          val base           = rawCommit(git, Map("a.txt" -> "a\n", "b.txt" -> "b\n"), Seq.empty, "base")
          val p1             = rawCommit(git, Map("a.txt" -> "a changed\n", "b.txt" -> "b\n"), Seq(base), "p1")
          val p2             = rawCommit(git, Map("a.txt" -> "a\n", "b.txt" -> "b changed\n"), Seq(base), "p2")
          val m              = rawCommit(git, Map("a.txt" -> "a changed\n", "b.txt" -> "b changed\n"), Seq(p1, p2), "merge")
          val Right(changes) = GitDiff.mergeCombinedChanges(git.getRepository, m): @unchecked
          assert(changes.isEmpty)
        }
      }

      test("conflict resolution lines absent from all parents are added") {
        withRepo { (_, git) =>
          val base           = rawCommit(git, Map("a.txt" -> "a\n"), Seq.empty, "base")
          val p1             = rawCommit(git, Map("a.txt" -> "a p1\n"), Seq(base), "p1")
          val p2             = rawCommit(git, Map("a.txt" -> "a p2\n"), Seq(base), "p2")
          val m              = rawCommit(git, Map("a.txt" -> "a resolved NOSONAR\n"), Seq(p1, p2), "merge")
          val Right(changes) = GitDiff.mergeCombinedChanges(git.getRepository, m): @unchecked
          val f              = changes.find(_.path == "a.txt").get
          assert(f.added.map(_.text) == Vector("a resolved NOSONAR"))
        }
      }

      test("line removed relative to all parents is removed") {
        withRepo { (_, git) =>
          val base           = rawCommit(git, Map("a.txt" -> "keep\ndoomed\n"), Seq.empty, "base")
          val p1             = rawCommit(git, Map("a.txt" -> "keep\ndoomed\np1\n"), Seq(base), "p1")
          val p2             = rawCommit(git, Map("a.txt" -> "keep\ndoomed\np2\n"), Seq(base), "p2")
          val m              = rawCommit(git, Map("a.txt" -> "keep\np1\np2\n"), Seq(p1, p2), "merge")
          val Right(changes) = GitDiff.mergeCombinedChanges(git.getRepository, m): @unchecked
          val f              = changes.find(_.path == "a.txt").get
          assert(f.removed.map(_.text) == Vector("doomed"))
        }
      }

      test("line one parent already deleted is not removed by the merge") {
        withRepo { (_, git) =>
          val base           = rawCommit(git, Map("a.txt" -> "keep\ndoomed\n"), Seq.empty, "base")
          val p1             = rawCommit(git, Map("a.txt" -> "keep\ndoomed\n"), Seq(base), "p1")
          val p2             = rawCommit(git, Map("a.txt" -> "keep\n"), Seq(base), "p2 already deleted")
          val m              = rawCommit(git, Map("a.txt" -> "keep\n"), Seq(p1, p2), "merge")
          val Right(changes) = GitDiff.mergeCombinedChanges(git.getRepository, m): @unchecked
          assert(!changes.exists(_.removed.exists(_.text == "doomed")))
        }
      }
    }

    test("fixtures are hermetic: host git config is invisible") {
      withRepo { (_, git) =>
        val userCfg = SystemReader.getInstance().getUserConfig()
        assert(Option(userCfg.getString("commit", null, "gpgsign")).isEmpty)
        assert(!git.getRepository.getConfig.getBoolean("commit", "gpgsign", false))
      }
    }
  }
}

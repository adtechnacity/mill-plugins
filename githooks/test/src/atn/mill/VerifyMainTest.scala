package atn.mill

import utest._

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.RevWalk

object VerifyMainTest extends TestSuite {

  import GitFixtures._

  val trustedKeysPath = ".mill-signing/trusted-keys"

  def sha(id: ObjectId) = id.getName

  def commitOf(git: Git, id: ObjectId) = {
    val walk = new RevWalk(git.getRepository)
    try walk.parseCommit(id)
    finally walk.close()
  }

  /**
   * `VerifyMain` only reads objects/refs — it doesn't care whether the repo is bare, so a fixture's own `.git` dir
   * doubles as the "server" repo a pre-receive hook would run against.
   */
  def gitDirOf(git: Git) = os.Path(git.getRepository.getDirectory)

  def keyFile(name: String, ring: org.bouncycastle.openpgp.PGPPublicKeyRing) =
    s"$trustedKeysPath/$name.asc" -> armorRing(ring)

  /** Commits `signer`'s key into the trust store; returns the new tip, the base a pushed commit builds on. */
  def trustKey(git: Git, dir: os.Path, signer: org.bouncycastle.openpgp.PGPKeyPair): ObjectId = {
    val (path, armored) = keyFile("signer", keyRing(signer))
    commitFile(git, dir, path, armored, "add key")
    git.getRepository.resolve("HEAD")
  }

  /** `commitsToVerify` over `c1..c3` — two commits — under the ceiling `maxCommits`. */
  def twoCommitRange(maxCommits: Int) = withRepo { (dir, git) =>
    val c1 = commitFile(git, dir, "a.txt", "1", "c1")
    commitFile(git, dir, "a.txt", "2", "c2")
    val c3 = commitFile(git, dir, "a.txt", "3", "c3")
    VerifyMain.commitsToVerify(
      git.getRepository,
      VerifyMain.RefUpdate(c1.getName, c3.getName, "refs/heads/main"),
      maxCommits
    )
  }

  val tests = Tests {

    test("a trusted-signed commit tripping a marker condition passes (exit 0)") {
      withRepo { (dir, git) =>
        val signer  = genKeyPair()
        val mainTip = trustKey(git, dir, signer)
        val pushed  = signedCommit(git, Map("Foo.scala" -> "// NOSONAR\n"), Seq(mainTip), "add suppression", signer)

        val (code, failures) =
          VerifyMain.run(
            gitDirOf(git),
            None,
            trustedKeysPath,
            Iterator(s"${sha(mainTip)} ${sha(pushed)} refs/heads/main")
          )
        code ==> 0
        failures ==> Vector.empty
      }
    }

    test("an unsigned commit tripping a condition fails, naming the commit and the condition") {
      withRepo { (dir, git) =>
        val signer  = genKeyPair()
        val mainTip = trustKey(git, dir, signer)
        val pushed  = rawCommit(git, Map("Foo.scala" -> "// NOSONAR\n"), Seq(mainTip), "add suppression")

        val (code, failures) =
          VerifyMain.run(
            gitDirOf(git),
            None,
            trustedKeysPath,
            Iterator(s"${sha(mainTip)} ${sha(pushed)} refs/heads/main")
          )
        code ==> 1
        assert(
          failures.exists(f =>
            f.contains(sha(pushed).take(8)) && f.contains("unsigned") && f.contains("exception-comments")
          )
        )
      }
    }

    test("a key added under the trust store, signed by an already-trusted key, passes") {
      withRepo { (dir, git) =>
        val signerA         = genKeyPair()
        commitFile(git, dir, keyFile("a", keyRing(signerA))._1, keyFile("a", keyRing(signerA))._2, "add key A")
        val mainTip         = git.getRepository.resolve("HEAD")
        val (path, armored) = keyFile("b", keyRing(genKeyPair()))
        val pushed          = signedCommit(git, Map(path -> armored), Seq(mainTip), "add key B", signerA)

        val (code, failures) =
          VerifyMain.run(
            gitDirOf(git),
            None,
            trustedKeysPath,
            Iterator(s"${sha(mainTip)} ${sha(pushed)} refs/heads/main")
          )
        code ==> 0
        failures ==> Vector.empty
      }
    }

    test("a key added under the trust store, signed by the key it introduces, is untrusted") {
      withRepo { (dir, git) =>
        val signerA         = genKeyPair()
        commitFile(git, dir, keyFile("a", keyRing(signerA))._1, keyFile("a", keyRing(signerA))._2, "add key A")
        val mainTip         = git.getRepository.resolve("HEAD")
        val signerB         = genKeyPair()
        val (path, armored) = keyFile("b", keyRing(signerB))
        val pushed          = signedCommit(git, Map(path -> armored), Seq(mainTip), "add key B", signerB)

        val (code, failures) =
          VerifyMain.run(
            gitDirOf(git),
            None,
            trustedKeysPath,
            Iterator(s"${sha(mainTip)} ${sha(pushed)} refs/heads/main")
          )
        code ==> 1
        assert(failures.exists(_.contains("untrusted")))
      }
    }

    test("a new branch (zero old-sha) introducing its own key is judged untrusted against the pre-existing trust root") {
      withRepo { (dir, git) =>
        val signerA         = genKeyPair()
        commitFile(git, dir, keyFile("a", keyRing(signerA))._1, keyFile("a", keyRing(signerA))._2, "add key A")
        val mainTip         = git.getRepository.resolve("HEAD")
        val signerB         = genKeyPair()
        val (path, armored) = keyFile("b", keyRing(signerB))
        // Not reachable from any ref: built off mainTip but no ref points at it (a branch always forks off existing
        // history — this is exactly what a real new-branch push looks like).
        val pushed          = signedCommit(git, Map(path -> armored), Seq(mainTip), "add key B", signerB)

        val (code, failures) =
          VerifyMain.run(
            gitDirOf(git),
            None,
            trustedKeysPath,
            Iterator(s"${VerifyMain.ZeroId} ${sha(pushed)} refs/heads/feature")
          )
        code ==> 1
        assert(failures.exists(_.contains("untrusted")))
      }
    }

    test("a deleted branch (zero new-sha) is skipped without touching the trust root") {
      withRepo { (dir, git) =>
        val mainTip = commitFile(git, dir, "a.txt", "x", "init")

        val (code, failures) = VerifyMain.run(
          gitDirOf(git),
          None,
          trustedKeysPath,
          Iterator(s"${mainTip.getName} ${VerifyMain.ZeroId} refs/heads/gone")
        )
        code ==> 0
        failures ==> Vector.empty
      }
    }

    test("an unprotected-only push passes even when the trust store never existed (bootstrap-unaffected)") {
      withRepo { (dir, git) =>
        val mainTip = commitFile(git, dir, "a.txt", "x", "init")
        val pushed  = rawCommit(git, Map("a.txt" -> "y"), Seq(mainTip), "unprotected change")

        val (code, failures) =
          VerifyMain.run(
            gitDirOf(git),
            None,
            trustedKeysPath,
            Iterator(s"${mainTip.getName} ${sha(pushed)} refs/heads/main")
          )
        code ==> 0
        failures ==> Vector.empty
      }
    }

    test("a protected push against a genuinely missing trust store is a configuration error naming the path") {
      withRepo { (dir, git) =>
        val mainTip = commitFile(git, dir, "a.txt", "x", "init")
        val pushed  = rawCommit(git, Map("Foo.scala" -> "// NOSONAR\n"), Seq(mainTip), "add suppression")

        val (code, failures) =
          VerifyMain.run(
            gitDirOf(git),
            None,
            trustedKeysPath,
            Iterator(s"${mainTip.getName} ${sha(pushed)} refs/heads/main")
          )
        code ==> 1
        assert(failures.exists(f => f.contains("signing configuration error") && f.contains(trustedKeysPath)))
      }
    }

    test("a range exceeding the commit ceiling fails closed") {
      val result = twoCommitRange(maxCommits = 1)
      assert(result.isLeft)
      assert(result.left.exists(_.contains("resource ceiling")))
    }

    test("a range within the commit ceiling is returned in full") {
      val result = twoCommitRange(maxCommits = 2)
      assert(result.exists(_.size == 2))
    }

    test("a malformed stdin line is a clear error, not an exception") {
      withRepo { (dir, git) =>
        commitFile(git, dir, "a.txt", "x", "init")
        val (code, failures) = VerifyMain.run(gitDirOf(git), None, trustedKeysPath, Iterator("garbage line"))
        code ==> 1
        assert(failures.exists(_.contains("malformed")))
      }
    }

    test("parseUpdates rejects a non-hex sha and a wrong field count") {
      VerifyMain.parseUpdates(Iterator("not-a-sha " + "0" * 40 + " refs/heads/main")).isLeft ==> true
      VerifyMain.parseUpdates(Iterator("a" * 40 + " " + "b" * 40)).isLeft ==> true
    }

    test("smoke: VerifyMain on its own runtime classpath, no Mill API, reproduces a failing verdict end-to-end") {
      // The build stages githooks' runtime classpath (core, jgit, BouncyCastle, os-lib, mainargs; never mill-libs, which
      // is provided scope) — exactly what a server's pre-receive hook gets, so a stray `mill.*` reference fails here.
      val classpath = sys.env.getOrElse(
        "VERIFY_MAIN_CLASSPATH",
        throw new java.lang.AssertionError("VERIFY_MAIN_CLASSPATH is not staged; run this suite through ./mill")
      )
      val javaBin   = os.Path(sys.props("java.home")) / "bin" / "java"
      withRepo { (dir, git) =>
        val mainTip = trustKey(git, dir, genKeyPair())
        val pushed  = rawCommit(git, Map("Foo.scala" -> "// NOSONAR\n"), Seq(mainTip), "add suppression")

        val result = os
          .proc(javaBin, "-cp", classpath, "atn.mill.VerifyMain", "--repo", gitDirOf(git).toString)
          .call(
            cwd = gitDirOf(git),
            stdin = s"${sha(mainTip)} ${sha(pushed)} refs/heads/main\n",
            check = false,
            mergeErrIntoOut = true
          )
        assert(result.exitCode == 1)
        assert(result.out.text().contains("unsigned"))
      }
    }
  }
}

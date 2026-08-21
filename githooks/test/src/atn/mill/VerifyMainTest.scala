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

  val tests = Tests {

    test("a trusted-signed commit tripping a marker condition passes (exit 0)") {
      withRepo { (dir, git) =>
        val signer  = genKeyPair()
        commitFile(git, dir, keyFile("signer", keyRing(signer))._1, keyFile("signer", keyRing(signer))._2, "add key")
        val mainTip = git.getRepository.resolve("HEAD")
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
        commitFile(git, dir, keyFile("signer", keyRing(signer))._1, keyFile("signer", keyRing(signer))._2, "add key")
        val mainTip = git.getRepository.resolve("HEAD")
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
      withRepo { (dir, git) =>
        val c1     = commitFile(git, dir, "a.txt", "1", "c1")
        commitFile(git, dir, "a.txt", "2", "c2")
        val c3     = commitFile(git, dir, "a.txt", "3", "c3")
        val update = VerifyMain.RefUpdate(c1.getName, c3.getName, "refs/heads/main")

        val result = VerifyMain.commitsToVerify(git.getRepository, update, maxCommits = 1)
        assert(result.isLeft)
        assert(result.left.exists(_.contains("resource ceiling")))
      }
    }

    test("a range within the commit ceiling is returned in full") {
      withRepo { (dir, git) =>
        val c1     = commitFile(git, dir, "a.txt", "1", "c1")
        commitFile(git, dir, "a.txt", "2", "c2")
        val c3     = commitFile(git, dir, "a.txt", "3", "c3")
        val update = VerifyMain.RefUpdate(c1.getName, c3.getName, "refs/heads/main")

        val result = VerifyMain.commitsToVerify(git.getRepository, update, maxCommits = 2)
        assert(result.exists(_.size == 2))
      }
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

    test("smoke: a coursier launch of the locally-staged artifact reproduces a failing verdict end-to-end") {
      // A bare `cs` on PATH is not safe to trust here: this machine's PATH resolves it to CodeScene's CLI, not
      // coursier (the exact collision the plan called out) — resolve coursier's own self-install path explicitly.
      val coursierBin = Seq(
        sys.env.get("COURSIER_BIN"),
        Some((os.home / ".local/share/coursier/bin/cs").toString),
        Some((os.home / ".local/share/coursier/bin/coursier").toString)
      ).flatten.find(p => os.exists(os.Path(p)))

      (coursierBin, sys.env.get("COURSIER_REPOSITORIES"), sys.env.get("PLUGIN_VERSION")) match {
        case (Some(cs), Some(repos), Some(version)) =>
          withRepo { (dir, git) =>
            val signer  = genKeyPair()
            commitFile(git, dir, keyFile("signer", keyRing(signer))._1, keyFile("signer", keyRing(signer))._2, "add key")
            val mainTip = git.getRepository.resolve("HEAD")
            val pushed  = rawCommit(git, Map("Foo.scala" -> "// NOSONAR\n"), Seq(mainTip), "add suppression")

            val result = os
              .proc(
                cs,
                "launch",
                s"com.adtechnacity:mill-githooks_mill1_3:$version",
                "--main-class",
                "atn.mill.VerifyMain",
                "--",
                "--repo",
                gitDirOf(git).toString
              )
              .call(
                cwd = gitDirOf(git),
                env = Map("COURSIER_REPOSITORIES" -> repos),
                stdin = s"${sha(mainTip)} ${sha(pushed)} refs/heads/main\n",
                check = false,
                mergeErrIntoOut = true
              )
            assert(result.exitCode != 0)
            assert((result.out.text() + result.err.text()).contains("unsigned"))
          }
        case _                                      =>
          () // coursier binary not found, or COURSIER_REPOSITORIES/PLUGIN_VERSION not staged for this test run
      }
    }
  }
}

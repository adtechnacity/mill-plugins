package atn.mill

import utest._
import mill._
import mill.api.{Discover, Result}
import mill.testkit.{TestRootModule, UnitTester}
import mill.api.daemon.ExecResult

import org.eclipse.jgit.revwalk.RevWalk

/**
 * Tests for GitHooksModule. These tests can directly access and test the GitHooksModule code.
 */
object GitHooksModuleTest extends TestSuite:

  val tests = Tests:

    test("headBranch - returns current branch") {
      GitRepo.headBranch() match {
        case Result.Success(branch) =>
          assert(branch.nonEmpty)
          assert(!branch.contains("\n"))
        case f: Result.Failure      =>
        // Acceptable failure in non-git environments
      }
    }

    test("headSHA - returns current commit SHA") {
      GitRepo.headSHA() match {
        case Result.Success(sha) =>
          assert(sha.nonEmpty)
          assert(sha.length == 40)
          assert(sha.forall(c => c.isDigit || (c >= 'a' && c <= 'f')))
        case f: Result.Failure   =>
      }
    }

    test("headTag - returns tag or commit reference") {
      GitRepo.headTag() match {
        case Result.Success(tag) =>
          assert(tag.nonEmpty)
        case f: Result.Failure   =>
      }
    }

    test("repo - can initialize git repository") {
      GitRepo.repo match {
        case Result.Success(r) =>
          assert(r.getDirectory().exists())
          assert(r.getDirectory().isDirectory())
        case f: Result.Failure =>
          assert(false)
      }
    }

    test("prePush - propagates test failures") {
      UnitTester(PrePushFailingBuild, os.temp.dir()).scoped { eval =>
        eval("prePush") match {
          case Left(f: ExecResult.Failure[?]) =>
            assert(f.msg.contains("Tests failed"))
          case r                              =>
            throw new java.lang.AssertionError(s"Expected failure but got $r")
        }
      }
    }

    test("prePush - passes with successful tests") {
      UnitTester(PrePushPassingBuild, os.temp.dir()).scoped { eval =>
        val expectedResult = UnitTester.Result(Vector(()), 1)
        eval("prePush") match {
          case Right(r) if r == expectedResult =>
          case r                               =>
            throw new java.lang.AssertionError(s"Expected success but got failure: $r")
        }
      }
    }

    test("writePrePushHook - aborts the push when the test run fails") {
      // The generated hook calls selective.run/git.prePush directly; without `set -e` a
      // failing test run is masked by the trailing selective.prepare and the push proceeds.
      val dir    = os.temp.dir()
      val hook   = dir / "pre-push"
      new GitInstall(dir, mill.api.daemon.Logger.DummyLogger).writePrePushHook(hook)
      val script = os.read(hook)

      val setEIdx    = script.indexOf("set -e")
      val runIdx     = script.indexOf("selective.run __.test")
      val prepareIdx = script.indexOf("selective.prepare __.test")

      assert(setEIdx >= 0)        // failures must abort the script
      assert(setEIdx < runIdx)    // guard is in effect before the test run
      assert(runIdx < prepareIdx) // snapshot update only after a passing run
    }

    test("writePrePushHook - injects prePushExtraCommands as gates before the test run") {
      val dir    = os.temp.dir()
      val hook   = dir / "pre-push"
      new GitInstall(dir, mill.api.daemon.Logger.DummyLogger, prePushExtraCommands = Seq("./mill codeHealth"))
        .writePrePushHook(hook)
      val script = os.read(hook)

      val setEIdx = script.indexOf("set -e")
      val gateIdx = script.indexOf("./mill codeHealth")
      val runIdx  = script.indexOf("selective.run __.test")

      assert(gateIdx >= 0)      // the extra gate is present
      assert(setEIdx < gateIdx) // under `set -e`, so a non-zero gate aborts the push
      assert(gateIdx < runIdx)  // fast-fail: gate runs before the slow test run
    }

    test("writePrePushHook - snapshot covers the configured selectiveSnapshotTasks") {
      // The snapshot must be a superset of every selective.run selector; a too-narrow snapshot makes
      // pre-commit's selective format/scalafix run on every module (absent inputs count as changed).
      val dir    = os.temp.dir()
      val hook   = dir / "pre-push"
      new GitInstall(
        dir,
        mill.api.daemon.Logger.DummyLogger,
        selectiveSnapshotTasks = Seq("__.test", "__.checkFormat", "__.scalafixCheck")
      ).writePrePushHook(hook)
      val script = os.read(hook)

      // space-separated varargs to selective.prepare (NOT `+`, which would run them as separate tasks)
      assert(script.contains("selective.prepare __.test __.checkFormat __.scalafixCheck"))
    }

    test("writePreCommitHook - selective with full fallback when selectivePreCommitTasks set") {
      val dir    = os.temp.dir()
      val hook   = dir / "pre-commit"
      new GitInstall(
        dir,
        mill.api.daemon.Logger.DummyLogger,
        selectivePreCommitTasks = Seq("__.checkFormat", "__.scalafixCheck")
      ).writePreCommitHook(hook)
      val script = os.read(hook)

      assert(script.contains("set -e"))
      assert(script.contains("if [ -f \"$SELECTIVE_JSON\" ]; then"))
      assert(script.contains("selective.run __.checkFormat __.scalafixCheck")) // snapshot present
      assert(script.contains("__.checkFormat + __.scalafixCheck"))             // first-run fallback
      assert(!script.contains("git.preCommit"))                                // replaced, not appended
    }

    test("writePreCommitHook - keeps legacy git.preCommit when selectivePreCommitTasks empty") {
      val dir    = os.temp.dir()
      val hook   = dir / "pre-commit"
      new GitInstall(dir, mill.api.daemon.Logger.DummyLogger).writePreCommitHook(hook)
      val script = os.read(hook)

      assert(script.contains("git.preCommit"))
      assert(!script.contains("selective.run")) // no selective block in the legacy path
    }

    test("writePreCommitHook - inactive install carries no signing lines (R8)") {
      val dir  = os.temp.dir()
      val hook = dir / "pre-commit"
      new GitInstall(dir, mill.api.daemon.Logger.DummyLogger).writePreCommitHook(hook)
      assert(!os.read(hook).contains("git.checkSigning"))
    }

    test("writePreCommitHook - active install gains the checkSigning line, index passed explicitly") {
      val dir    = os.temp.dir()
      val hook   = dir / "pre-commit"
      new GitInstall(dir, mill.api.daemon.Logger.DummyLogger, signingActive = true).writePreCommitHook(hook)
      val script = os.read(hook)

      assert(script.contains("git.checkSigning --index \"$GIT_INDEX_FILE\""))
      assert(script.indexOf("set -e") < script.indexOf("git.checkSigning"))
    }

    test("writePrePushHook - inactive install carries no signing block (R8)") {
      val dir  = os.temp.dir()
      val hook = dir / "pre-push"
      new GitInstall(dir, mill.api.daemon.Logger.DummyLogger).writePrePushHook(hook)
      assert(!os.read(hook).contains("git.verifyRange"))
    }

    test("writePrePushHook - active install reads all of stdin and shields Mill invocations from it") {
      val dir    = os.temp.dir()
      val hook   = dir / "pre-push"
      new GitInstall(dir, mill.api.daemon.Logger.DummyLogger, signingActive = true).writePrePushHook(hook)
      val script = os.read(hook)

      assert(script.contains("while read local_ref local_sha remote_ref remote_sha"))
      assert(script.contains("refs/tags/*) continue"))
      assert(script.contains("git.verifyRange \"$remote_sha\" \"$local_sha\" --lenient < /dev/null"))

      val setEIdx    = script.indexOf("set -e")
      val signingIdx = script.indexOf("while read local_ref")
      val testRunIdx = script.indexOf("selective.run __.test")
      assert(setEIdx >= 0 && signingIdx >= 0 && testRunIdx >= 0)
      assert(setEIdx < signingIdx)    // signing check runs under the abort-on-failure guard
      assert(signingIdx < testRunIdx) // fail fast on a bad signature before the (slower) test run
    }

    test("activationDriftMessages - keys added since the last install, without --force, is flagged") {
      val dir = os.temp.dir()
      new GitInstall(dir, mill.api.daemon.Logger.DummyLogger).install(force = false) // inactive install
      val messages = new GitInstall(dir, mill.api.daemon.Logger.DummyLogger, signingActive = true)
        .activationDriftMessages(force = false)
      assert(messages.exists(_.contains("drifted")))
    }

    test("activationDriftMessages - no drift when hook state already matches signingActive") {
      val dir      = os.temp.dir()
      new GitInstall(dir, mill.api.daemon.Logger.DummyLogger, signingActive = true).install(force = false)
      val messages = new GitInstall(dir, mill.api.daemon.Logger.DummyLogger, signingActive = true)
        .activationDriftMessages(force = false)
      assert(messages.isEmpty)
    }

    test("activationDriftMessages - force suppresses the warning (writeNext will overwrite anyway)") {
      val dir      = os.temp.dir()
      new GitInstall(dir, mill.api.daemon.Logger.DummyLogger).install(force = false)
      val messages = new GitInstall(dir, mill.api.daemon.Logger.DummyLogger, signingActive = true)
        .activationDriftMessages(force = true)
      assert(messages.isEmpty)
    }

    test("checkSigningOutcome - staged suppression marker with commit.gpgsign unset rejects, naming the condition") {
      GitFixtures.withRepo { (dir, git) =>
        GitFixtures.commitFile(git, dir, "a.txt", "one\n", "base")
        os.write.over(dir / "a.txt", "one\nval x = 1 // NOSONAR\n")
        git.add().addFilepattern("a.txt").call()
        val result = GitHooksModule.checkSigningOutcome(git.getRepository, None, SigningConditions.defaults)
        assert(result.isLeft)
        assert(result.left.exists(_.contains("exception-comments")))
      }
    }

    test("checkSigningOutcome - passes once commit.gpgsign is set locally") {
      GitFixtures.withRepo { (dir, git) =>
        GitFixtures.commitFile(git, dir, "a.txt", "one\n", "base")
        os.write.over(dir / "a.txt", "one\nval x = 1 // NOSONAR\n")
        git.add().addFilepattern("a.txt").call()
        git.getRepository.getConfig.setBoolean("commit", null, "gpgsign", true)
        git.getRepository.getConfig.save()
        val result = GitHooksModule.checkSigningOutcome(git.getRepository, None, SigningConditions.defaults)
        assert(result.isRight)
      }
    }

    test("checkSigningOutcome - a temp index is only honored when passed explicitly (git commit -a shape)") {
      GitFixtures.withRepo { (dir, git) =>
        GitFixtures.commitFile(git, dir, "a.txt", "clean\n", "base")
        os.write.over(dir / "a.txt", "clean\nval x = 1 // NOSONAR\n")
        git.add().addFilepattern("a.txt").call()
        val tempIndex = os.temp(prefix = "alt-index")
        os.copy.over(dir / ".git" / "index", tempIndex)
        git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.MIXED).call()

        val withIndex =
          GitHooksModule.checkSigningOutcome(git.getRepository, Some(tempIndex), SigningConditions.defaults)
        assert(withIndex.isLeft)

        val withoutIndex = GitHooksModule.checkSigningOutcome(git.getRepository, None, SigningConditions.defaults)
        assert(withoutIndex.isRight)
      }
    }

    test("checkSigningOutcome - a merge in progress is skipped regardless of staged content") {
      GitFixtures.withRepo { (dir, git) =>
        GitFixtures.commitFile(git, dir, "a.txt", "one\n", "base")
        os.write.over(dir / "a.txt", "one\nval x = 1 // NOSONAR\n")
        git.add().addFilepattern("a.txt").call()
        os.write(dir / ".git" / "MERGE_HEAD", "0" * 40 + "\n")
        val result = GitHooksModule.checkSigningOutcome(git.getRepository, None, SigningConditions.defaults)
        assert(result.isRight)
      }
    }

    test("checkSigningOutcome - a custom condition is enforced identically to a built-in (R1)") {
      val secretsOnly = new SigningCondition {
        val name                                                         = "secrets-only"
        def appliesTo(changes: GitDiff.ChangeSet): Vector[SigningReason] =
          changes.collect { case f if f.path == "secret.txt" => SigningReason(name, s"${f.path}: touched") }
      }
      GitFixtures.withRepo { (dir, git) =>
        GitFixtures.commitFile(git, dir, "secret.txt", "one\n", "base")
        os.write.over(dir / "secret.txt", "one\ntwo\n")
        git.add().addFilepattern("secret.txt").call()
        val result = GitHooksModule.checkSigningOutcome(git.getRepository, None, Seq(secretsOnly))
        assert(result.isLeft)
        assert(result.left.exists(_.contains("secrets-only")))
      }
    }

    test("commitFailure - a trusted-signed commit under a protected path passes") {
      GitFixtures.withRepo { (_, git) =>
        val signer   = GitFixtures.genKeyPair()
        val keysDir  = os.temp.dir(prefix = "trusted-keys")
        os.write(keysDir / "key.asc", GitFixtures.armorRing(GitFixtures.keyRing(signer)))
        val commitId = GitFixtures.signedCommit(git, Map(".scalafmt.conf" -> "x"), Nil, "signed", signer)
        val walk     = new RevWalk(git.getRepository)
        val commit   =
          try walk.parseCommit(commitId)
          finally walk.close()
        val result   =
          GitHooksModule.commitFailure(git.getRepository, commit, SigningConditions.defaults, keysDir, lenient = false)
        assert(result.isEmpty)
      }
    }

    test("commitFailure - an unsigned commit under a protected path fails, naming commit and condition") {
      GitFixtures.withRepo { (dir, git) =>
        val keysDir = os.temp.dir(prefix = "trusted-keys")
        os.write(keysDir / "key.asc", GitFixtures.armorRing(GitFixtures.keyRing(GitFixtures.genKeyPair())))
        val commit  = GitFixtures.commitFile(git, dir, ".scalafmt.conf", "x", "unsigned change")
        val result  =
          GitHooksModule.commitFailure(git.getRepository, commit, SigningConditions.defaults, keysDir, lenient = false)
        assert(result.isDefined)
        assert(result.exists(_.contains("protected-paths")))
        assert(result.exists(_.contains(commit.getName.take(8))))
      }
    }

    test("commitFailure - a commit triggering no condition passes regardless of signature") {
      GitFixtures.withRepo { (dir, git) =>
        val keysDir = os.temp.dir(prefix = "trusted-keys")
        os.write(keysDir / "key.asc", GitFixtures.armorRing(GitFixtures.keyRing(GitFixtures.genKeyPair())))
        val commit  = GitFixtures.commitFile(git, dir, "plain.txt", "hello", "unrelated change")
        val result  =
          GitHooksModule.commitFailure(git.getRepository, commit, SigningConditions.defaults, keysDir, lenient = false)
        assert(result.isEmpty)
      }
    }

    test("commitFailure - a missing trust store passes lenient, fails as a config error when strict") {
      GitFixtures.withRepo { (dir, git) =>
        val missingKeysDir = os.temp.dir(prefix = "no-keys") / "does-not-exist"
        val commit         = GitFixtures.commitFile(git, dir, ".scalafmt.conf", "x", "unsigned change")

        val lenientResult =
          GitHooksModule.commitFailure(
            git.getRepository,
            commit,
            SigningConditions.defaults,
            missingKeysDir,
            lenient = true
          )
        assert(lenientResult.isEmpty)

        val strictResult = GitHooksModule.commitFailure(
          git.getRepository,
          commit,
          SigningConditions.defaults,
          missingKeysDir,
          lenient = false
        )
        assert(strictResult.isDefined)
        assert(strictResult.exists(_.contains("configuration error")))
      }
    }

object PrePushFailingBuild extends TestRootModule with GitHooksModule:
  def defaultTask(): String       = "prePush"
  def test                        = Task[String](throw new Exception("intentional test failure"))
  lazy val millDiscover: Discover = Discover[this.type]

object PrePushPassingBuild extends TestRootModule with GitHooksModule:
  def defaultTask(): String       = "prePush"
  def test                        = Task[String]("all tests passed 1")
  lazy val millDiscover: Discover = Discover[this.type]

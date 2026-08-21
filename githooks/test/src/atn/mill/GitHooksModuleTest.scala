package atn.mill

import utest._
import mill._
import mill.api.{Discover, ExecResult, Result}
import mill.testkit.{TestRootModule, UnitTester}

import UnitTesterSupport.{failureOf, withBuild}

import org.eclipse.jgit.revwalk.RevWalk

/**
 * [[GitHooksModule]] as a build sees it: its defaults, module discovery, and the hook commands driven through Mill's
 * `UnitTester`. The commit-message commands open the enclosing checkout through `GitRepo`, so they run inside one.
 */
object GitHooksModuleTest extends TestSuite:

  /** A workspace whose `.git/hooks` directory exists, the one thing `install` needs from a checkout. */
  private def workspaceWithHooksDir(): os.Path =
    val ws = os.temp.dir()
    os.makeDir.all(ws / ".git" / "hooks")
    ws

  /** The `WorkDone` value an `install` run returned. */
  private def workDone(result: Either[ExecResult.Failing[?], UnitTester.Result[Seq[?]]]): Int = result match
    case Right(UnitTester.Result(Seq(done: WorkDone), _)) => done.value
    case other                                            => throw new java.lang.AssertionError(s"expected WorkDone, got $other")

  /** A commit message file holding `content`, in a fresh directory. */
  private def messageFile(content: String): String =
    val file = os.temp.dir() / "COMMIT_EDITMSG"
    os.write(file, content)
    file.toString

  val tests = Tests:

    test("defaults - every knob has its documented default and install is the external module's default task") {
      val m = new HooksBuild()
      assert(m.preCommitExtraCommands.isEmpty)
      assert(m.prePushExtraCommands.isEmpty)
      assert(m.selectiveSnapshotTasks == Seq("__.test"))
      assert(m.selectivePreCommitTasks.isEmpty)
      assert(m.emailDomain == "")
      assert(m.commitFooterPattern.isEmpty)
      assert(m.ollamaUrl == "http://localhost:11434")
      assert(m.ollamaModel == "qwen3:8b")
      assert(m.conventionalCommitTypes == GitValidateCommit.DefaultTypes)
      assert(m.excludedModuleNames == Set("test", "integration"))
      assert(m.prePushTasks == Seq("__.test"))
      assert(GitHooksModule.defaultTask() == "install")
    }

    test("validModules - lowercase modules at any depth, minus excludedModuleNames, plus mill-build") {
      val build  = new HooksBuild()
      val narrow = new NarrowBuild()
      assert(build.validModules(build) == Set("core", "app", "sub", "mill-build"))
      assert(narrow.validModules(narrow) == Set("app", "sub", "test", "integration", "mill-build"))
    }

    test("head* - delegate to GitRepo for the enclosing checkout") {
      val m = new HooksBuild()
      assert(GitRepo.repo.isInstanceOf[Result.Success[?]])
      assert(m.headBranch() == GitRepo.headBranch())
      assert(m.headSHA() == GitRepo.headSHA())
      assert(m.headTag() == GitRepo.headTag())
    }

    test("install - writes the four hooks under the workspace's .git/hooks, again only when forced") {
      withBuild(new HooksBuild(), workspaceWithHooksDir()) { (build, eval) =>
        assert(workDone(eval("install")) == 15)
        assert(os.read(build.moduleDir / ".git" / "hooks" / "commit-msg").contains("git.validateCommit"))
        assert(workDone(eval("install")) == 0)
        assert(workDone(eval("install", "--force", "true")) == 15)
      }
    }

    test("install - fails naming the hook when the workspace has no .git/hooks directory") {
      withBuild(new HooksBuild()) { (build, eval) =>
        val msg = failureOf(eval("install"))
        assert(msg.startsWith(s"${build.moduleDir / ".git" / "hooks" / "pre-commit"} was not written"))
      }
    }

    test("validateCommit - accepts a scope that is a module of the build and rejects one that is not") {
      withBuild(new HooksBuild()) { (_, eval) =>
        assert(eval("validateCommit", "--file", messageFile("feat(core): add the greeting\n")).isRight)
        val msg = failureOf(eval("validateCommit", "--file", messageFile("feat(nope): add the greeting\n")))
        assert(msg.contains("* nope is not a valid module"))
      }
    }

    test("validateCommit - conventionalCommitTypes narrows the accepted types") {
      withBuild(new NarrowBuild()) { (_, eval) =>
        val msg = failureOf(eval("validateCommit", "--file", messageFile("fix(app): repair the greeting\n")))
        assert(msg.contains("* fix is not a valid type"))
      }
    }

    test("prepCommit - a non-commit source leaves the message file untouched") {
      withBuild(new HooksBuild()) { (_, eval) =>
        val file = messageFile("Merge branch 'topic'\n")
        assert(eval("prepCommit", "--file", file, "--source", "merge").isRight)
        assert(os.read(os.Path(file)) == "Merge branch 'topic'\n")
      }
    }

    test("prepCommit - the default source is commit: the template is wrapped around the message") {
      // NarrowBuild points ollamaUrl at a closed port, so whatever the enclosing checkout has staged, no model answers
      // and the file gets the plain template.
      withBuild(new NarrowBuild()) { (_, eval) =>
        val file = messageFile("feat: draft\n")
        assert(eval("prepCommit", "--file", file).isRight)
        val out  = os.read(os.Path(file))
        assert(out.startsWith("\n#\nfeat: draft\n#We use conventional commits"))
        assert(out.contains("\n#Valid scopes are: "))
        assert(out.contains("\"mill-build\""))
      }
    }

    test("prePush - propagates test failures") {
      UnitTester(PrePushFailingBuild, os.temp.dir()).scoped { eval =>
        assert(failureOf(eval("prePush")).contains("Tests failed"))
      }
    }

    test("prePush - passes with successful tests") {
      UnitTester(PrePushPassingBuild, os.temp.dir()).scoped { eval =>
        assert(eval("prePush") == Right(UnitTester.Result(Vector(()), 1)))
      }
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

// --- Fixtures ---

// The modules are nested in a class rather than in an object: Scala 3 compiles objects nested in an object to static
// fields that Mill's reflective child discovery does not see, whereas a real build.mill is wrapped in a class by
// Mill's codegen and reflects fine.
abstract class HooksRoot extends TestRootModule with GitHooksModule:
  def defaultTask(): String = "install"
  object core        extends Module
  object app         extends Module:
    object sub  extends Module
    object test extends Module
  object Docs        extends Module
  object integration extends Module

class HooksBuild extends HooksRoot:
  lazy val millDiscover: Discover = Discover[this.type]

/** Every knob moved off its default; ollamaUrl is a closed port, so no model is ever reached. */
class NarrowBuild extends HooksRoot:
  override def excludedModuleNames     = Set("core")
  override def conventionalCommitTypes = List("feat")
  override def ollamaUrl               = "http://127.0.0.1:1"
  lazy val millDiscover: Discover      = Discover[this.type]

object PrePushFailingBuild extends TestRootModule with GitHooksModule:
  def defaultTask(): String       = "prePush"
  def test                        = Task[String](throw new Exception("intentional test failure"))
  lazy val millDiscover: Discover = Discover[this.type]

object PrePushPassingBuild extends TestRootModule with GitHooksModule:
  def defaultTask(): String       = "prePush"
  def test                        = Task[String]("all tests passed 1")
  lazy val millDiscover: Discover = Discover[this.type]

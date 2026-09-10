package atn.mill

import utest._
import mill._
import mill.api.{Discover, ExecResult, Result}
import mill.testkit.{TestRootModule, UnitTester}

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

  /** Runs `body` with a UnitTester over a fresh `HooksBuild` copied from `workspace`. */
  private def withBuild[T](build: HooksRoot = new HooksBuild(), workspace: os.Path = os.temp.dir())(
    body: (HooksRoot, UnitTester) => T
  ): T =
    UnitTester(build, workspace).scoped(eval => body(build, eval))

  /** The message the failed run `result` reports; a success is a test failure. */
  private def failureOf(result: Either[ExecResult.Failing[?], ?]): String = result match
    case Left(ExecResult.Failure(msg, _)) => msg
    case other                            => throw new java.lang.AssertionError(s"expected a failure, got $other")

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
      withBuild(workspace = workspaceWithHooksDir()) { (build, eval) =>
        assert(workDone(eval("install")) == 15)
        assert(os.read(build.moduleDir / ".git" / "hooks" / "commit-msg").contains("git.validateCommit"))
        assert(workDone(eval("install")) == 0)
        assert(workDone(eval("install", "--force", "true")) == 15)
      }
    }

    test("install - fails naming the hook when the workspace has no .git/hooks directory") {
      withBuild() { (build, eval) =>
        val msg = failureOf(eval("install"))
        assert(msg.startsWith(s"${build.moduleDir / ".git" / "hooks" / "pre-commit"} was not written"))
      }
    }

    test("validateCommit - accepts a scope that is a module of the build and rejects one that is not") {
      withBuild() { (_, eval) =>
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
      withBuild() { (_, eval) =>
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

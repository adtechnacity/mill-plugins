package atn.mill

import utest._
import mill._
import mill.api.{Discover, Result}
import mill.testkit.{TestRootModule, UnitTester}
import mill.api.daemon.ExecResult

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

object PrePushFailingBuild extends TestRootModule with GitHooksModule:
  def defaultTask(): String       = "prePush"
  def test                        = Task[String](throw new Exception("intentional test failure"))
  lazy val millDiscover: Discover = Discover[this.type]

object PrePushPassingBuild extends TestRootModule with GitHooksModule:
  def defaultTask(): String       = "prePush"
  def test                        = Task[String]("all tests passed 1")
  lazy val millDiscover: Discover = Discover[this.type]

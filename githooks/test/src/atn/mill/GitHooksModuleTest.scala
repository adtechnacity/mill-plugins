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

object PrePushFailingBuild extends TestRootModule with GitHooksModule:
  def defaultTask(): String       = "prePush"
  def test                        = Task[String](throw new Exception("intentional test failure"))
  lazy val millDiscover: Discover = Discover[this.type]

object PrePushPassingBuild extends TestRootModule with GitHooksModule:
  def defaultTask(): String       = "prePush"
  def test                        = Task[String]("all tests passed 1")
  lazy val millDiscover: Discover = Discover[this.type]

package atn.mill

import mill.api.Result
import mill.api.daemon.Logger.DummyLogger
import utest.*

import java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE

import scala.util.Success

/** [[GitInstall]] against real temporary directories: the scripts it writes and the way it fills a hooks directory. */
object GitInstallTest extends TestSuite:

  private def plain(dir: os.Path): GitInstall = new GitInstall(dir, DummyLogger)

  /** A fresh hooks directory and the installer `configure` builds for it. */
  private def installer(configure: os.Path => GitInstall = plain): (os.Path, GitInstall) =
    val dir = os.temp.dir()
    (dir, configure(dir))

  /** Writes the hook `name` with `write` into a fresh directory and returns the script. */
  private def hookScript(name: String, configure: os.Path => GitInstall = plain)(
    write: (GitInstall, os.Path) => WorkDone
  ): String =
    val (dir, install) = installer(configure)
    write(install, dir / name)
    os.read(dir / name)

  private def prePushScript(configure: os.Path => GitInstall = plain): String =
    hookScript("pre-push", configure)(_.writePrePushHook(_))

  private def preCommitScript(configure: os.Path => GitInstall = plain): String =
    hookScript("pre-commit", configure)(_.writePreCommitHook(_))

  /** What `install(force)` settles on: the summed [[WorkDone]] value, or the failure message. */
  private def installed(install: GitInstall, force: Boolean): Either[String, Int] = install.install(force) match
    case Success(Result.Success(done)) => Right(done.value)
    case Success(f: Result.Failure)    => Left(f.error)
    case other                         => throw new java.lang.AssertionError(s"install threw: $other")

  private val hookNames = Seq("pre-commit", "pre-push", "prepare-commit-msg", "commit-msg")

  /** The `WorkDone` value once every hook is written. */
  private val allHooks = 15

  val tests = Tests:

    test("install - fills an empty hooks directory with the four executable scripts") {
      val (dir, install) = installer()
      assert(installed(install, force = false) == Right(allHooks))
      val scripts        = hookNames.map(name => os.read(dir / name))
      val hookTasks      = Seq("git.preCommit", "git.prePush", "git.prepCommit --file $1", "git.validateCommit --file $1")
      assert(hookNames.forall(name => os.perms(dir / name).contains(OWNER_EXECUTE)))
      assert(scripts.forall(_.startsWith("#!/bin/sh\n")))
      assert(scripts.zip(hookTasks).forall((script, task) => script.contains(s"./mill $task\n")))
    }

    test("install - leaves an existing hook alone until forced") {
      val (dir, install) = installer()
      os.write(dir / "pre-commit", "custom hook")

      assert(installed(install, force = false) == Right(allHooks - 1))
      assert(os.read(dir / "pre-commit") == "custom hook")

      assert(installed(install, force = false) == Right(0))

      assert(installed(install, force = true) == Right(allHooks))
      assert(os.read(dir / "pre-commit").startsWith("#!/bin/sh\n"))
    }

    test("install - reports the first hook it cannot write and stops there") {
      val install = plain(os.temp("not a directory"))
      val msg     = installed(install, force = true).swap.getOrElse("")
      assert(msg.startsWith(s"${install.preCommitHookPath} was not written\n"))
      assert(!msg.contains("pre-push"))
    }

    test("prepare-commit-msg and commit-msg hooks - hand over git's message file, and the commit source when given") {
      assert(
        hookScript("prepare-commit-msg")(_.writePrepareCommitMsgHook(_)) ==
          "#!/bin/sh\n\nif [ -n \"$2\" ]; then\n  ./mill git.prepCommit --file $1 --source $2\nelse\n  ./mill git.prepCommit --file $1\nfi\n"
      )
      assert(hookScript("commit-msg")(_.writeCommitHook(_)) == "#!/bin/sh\n\n./mill git.validateCommit --file $1\n")
    }

    test("writePrePushHook - aborts the push when the test run fails") {
      // The generated hook calls selective.run/git.prePush directly; without `set -e` a
      // failing test run is masked by the trailing selective.prepare and the push proceeds.
      val script = prePushScript()

      val setEIdx    = script.indexOf("set -e")
      val runIdx     = script.indexOf("selective.run __.test")
      val prepareIdx = script.indexOf("selective.prepare __.test")

      assert(setEIdx >= 0)        // failures must abort the script
      assert(setEIdx < runIdx)    // guard is in effect before the test run
      assert(runIdx < prepareIdx) // snapshot update only after a passing run
    }

    test("writePrePushHook - prePushExtraCommands gate the test run; the snapshot covers selectiveSnapshotTasks") {
      val script = prePushScript(
        new GitInstall(
          _,
          DummyLogger,
          prePushExtraCommands = Seq("./mill codeHealth"),
          selectiveSnapshotTasks = Seq("__.test", "__.checkFormat", "__.scalafixCheck")
        )
      )

      val setEIdx = script.indexOf("set -e")
      val gateIdx = script.indexOf("./mill codeHealth")
      val runIdx  = script.indexOf("selective.run __.test")

      assert(gateIdx >= 0)      // the extra gate is present
      assert(setEIdx < gateIdx) // under `set -e`, so a non-zero gate aborts the push
      assert(gateIdx < runIdx)  // fast-fail: gate runs before the slow test run

      // The snapshot must be a superset of every selective.run selector; a too-narrow snapshot makes
      // pre-commit's selective format/scalafix run on every module (absent inputs count as changed).
      // Space-separated varargs to selective.prepare (NOT `+`, which would run them as separate tasks).
      assert(script.contains("selective.prepare __.test __.checkFormat __.scalafixCheck"))
    }

    test("writePreCommitHook - selectivePreCommitTasks replace git.preCommit: selective.run, full without a snapshot") {
      val script = preCommitScript(
        new GitInstall(_, DummyLogger, selectivePreCommitTasks = Seq("__.checkFormat", "__.scalafixCheck"))
      )

      assert(
        script.contains(
          "set -e\n./mill --meta-level 1 mill.scalalib.scalafmt.ScalafmtModule/checkFormatAll\n" +
            "SELECTIVE_JSON=\"out/mill-selective-execution.json\"\nif [ -f \"$SELECTIVE_JSON\" ]; then\n" +
            "  ./mill selective.run __.checkFormat __.scalafixCheck\nelse\n  ./mill __.checkFormat + __.scalafixCheck\nfi\n"
        )
      )
      assert(!script.contains("git.preCommit")) // replaced, not appended
    }

    test("writePreCommitHook - extra commands run under set -e, before the meta-level format check and git.preCommit") {
      val script = preCommitScript(new GitInstall(_, DummyLogger, preCommitExtraCommands = Seq("bash scan.sh")))
      assert(
        script.contains(
          "set -e\nbash scan.sh\n./mill --meta-level 1 mill.scalalib.scalafmt.ScalafmtModule/checkFormatAll\n" +
            "./mill git.preCommit\n"
        )
      )
      assert(!script.contains("selective.run")) // no selective block in the legacy path
    }

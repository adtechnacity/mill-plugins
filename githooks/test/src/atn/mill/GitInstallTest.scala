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

    test("hook paths - one script per git hook, directly under the hooks directory") {
      val (dir, install) = installer()
      val paths          =
        Seq(install.preCommitHookPath, install.prePushHookPath, install.prepareCommitHookPath, install.commitHookPath)
      assert(paths == hookNames.map(dir / _))
    }

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

    test("writeNext - adds the hook's work to the result so far, or keeps it when the hook is left alone") {
      val (dir, install) = installer()
      val existing       = dir / "commit-msg"
      os.write(existing, "keep")
      val written        = Result.Success[WorkDone](WrotePrePushHook)

      assert(install.writeNext(false, existing, _ => WroteCommitHook)(written) == Success(written))
      assert(
        install.writeNext(true, existing, _ => WroteCommitHook)(written).map(_.map(_.value)) == Success(
          Result.Success(10)
        )
      )
      assert(
        install.writeNext(false, dir / "pre-push", _ => WrotePrePushHook)(written).map(_.map(_.value)) == Success(
          Result.Success(4)
        )
      )
      assert(
        install.writeNext(false, dir / "pre-push", _ => WrotePrePushHook)(Result.Failure("earlier")) == Success(
          Result.Failure("earlier")
        )
      )
    }

    test("prepare-commit-msg hook - forwards the commit source only when git supplies one") {
      val script = hookScript("prepare-commit-msg")(_.writePrepareCommitMsgHook(_))
      assert(
        script == "#!/bin/sh\n\nif [ -n \"$2\" ]; then\n  ./mill git.prepCommit --file $1 --source $2\nelse\n  ./mill git.prepCommit --file $1\nfi\n"
      )
    }

    test("commit-msg hook - validates the message file git hands over") {
      assert(hookScript("commit-msg")(_.writeCommitHook(_)) == "#!/bin/sh\n\n./mill git.validateCommit --file $1\n")
    }

    test("selectiveOrFull - selective.run against the snapshot, a `+`-joined full run without one") {
      val (_, install) = installer()
      val script       = install.selectiveOrFull(Seq("__.checkFormat", "__.scalafixCheck"))
      assert(
        script.startsWith("SELECTIVE_JSON=\"out/mill-selective-execution.json\"\nif [ -f \"$SELECTIVE_JSON\" ]; then\n")
      )
      assert(script.contains("\n  ./mill selective.run __.checkFormat __.scalafixCheck\nelse\n"))
      assert(script.endsWith("\n  ./mill __.checkFormat + __.scalafixCheck\nfi"))
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

    test("writePrePushHook - injects prePushExtraCommands as gates before the test run") {
      val script = prePushScript(new GitInstall(_, DummyLogger, prePushExtraCommands = Seq("./mill codeHealth")))

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
      val script = prePushScript(
        new GitInstall(_, DummyLogger, selectiveSnapshotTasks = Seq("__.test", "__.checkFormat", "__.scalafixCheck"))
      )

      // space-separated varargs to selective.prepare (NOT `+`, which would run them as separate tasks)
      assert(script.contains("selective.prepare __.test __.checkFormat __.scalafixCheck"))
    }

    test("writePreCommitHook - selective with full fallback when selectivePreCommitTasks set") {
      val script = preCommitScript(
        new GitInstall(_, DummyLogger, selectivePreCommitTasks = Seq("__.checkFormat", "__.scalafixCheck"))
      )

      assert(script.contains("set -e"))
      assert(script.contains("if [ -f \"$SELECTIVE_JSON\" ]; then"))
      assert(script.contains("selective.run __.checkFormat __.scalafixCheck")) // snapshot present
      assert(script.contains("__.checkFormat + __.scalafixCheck"))             // first-run fallback
      assert(!script.contains("git.preCommit"))                                // replaced, not appended
    }

    test("writePreCommitHook - keeps legacy git.preCommit when selectivePreCommitTasks empty") {
      val script = preCommitScript()

      assert(script.contains("git.preCommit"))
      assert(!script.contains("selective.run")) // no selective block in the legacy path
    }

    test("writePreCommitHook - extra commands run under set -e, before the meta-level format check") {
      val script = preCommitScript(new GitInstall(_, DummyLogger, preCommitExtraCommands = Seq("bash scan.sh")))
      assert(
        script
          .contains("set -e\nbash scan.sh\n./mill --meta-level 1 mill.scalalib.scalafmt.ScalafmtModule/checkFormatAll\n")
      )
    }

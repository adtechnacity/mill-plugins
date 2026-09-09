package atn.mill

import mill.*
import mill.api.{BuildCtx, Discover, ExecResult}
import mill.api.daemon.Result
import mill.testkit.{TestRootModule, UnitTester}

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.{PersonIdent, Repository}

import utest.*

import java.io.{OutputStream, PrintStream}
import java.time.LocalDate
import scala.jdk.CollectionConverters.*

/**
 * Drives [[ReleaseModule]]'s commands through Mill's `UnitTester` over scripted jgit repositories. `UnitTester` copies
 * the scripted repository into the fixture's module directory and the fixture opens that copy, so no test touches the
 * repository this build lives in.
 */
object ReleaseModuleTest extends TestSuite:

  private val author = new PersonIdent("Release Test", "release@example.com")

  private def today: String = LocalDate.now().toString

  /** A throwaway repository: `commit` records `message` (appending it to a tracked file), `tag` tags HEAD. */
  final class Scripted:
    val dir: os.Path = os.temp.dir()
    private val git  = Git.init().setDirectory(dir.toIO).setInitialBranch("main").call()

    def commit(message: String): Scripted =
      os.write.append(dir / "history.txt", s"$message\n")
      git.add().addFilepattern("history.txt").call()
      git.commit().setMessage(message).setAuthor(author).setCommitter(author).call()
      this

    def tag(name: String): Scripted =
      git.tag().setName(name).setTagger(author).setMessage(name).call()
      this

    def version(content: String): Scripted =
      os.write.over(dir / "version", content)
      this

    def changelog(content: String): Scripted =
      os.write.over(dir / "CHANGELOG.md", content)
      this

  /** `chore: initial` tagged `v1.2.3`, then one commit per message in `after`. */
  private def taggedV123(after: String*): Scripted =
    after.foldLeft(Scripted().commit("chore: initial").tag("v1.2.3"))(_.commit(_))

  /** A fix and a scoped feat after `v1.2.3`. */
  private def fixAndFeat: Scripted = taggedV123("fix: correct a bug", "feat(core): add thing")

  /** `feat: old` under `v1.0.0`, `fix: new` after it, and the version file at `1.0.1-SNAPSHOT`. */
  private def oldAndNew: Scripted =
    Scripted().commit("feat: old").tag("v1.0.0").commit("fix: new").version("1.0.1-SNAPSHOT\n")

  /** What a command left behind: the build it ran on and its result. */
  final case class Outcome[T](build: ReleaseRoot, result: Either[ExecResult.Failing[T], UnitTester.Result[T]]):
    def value: T = result.fold(f => throw new java.lang.AssertionError(s"expected success, got $f"), _.value)

    def failure: String = result match
      case Left(ExecResult.Exception(t, _)) => t.getMessage
      case Left(f: ExecResult.Failure[?])   => f.msg
      case Right(r)                         => throw new java.lang.AssertionError(s"expected a failure, got $r")

    private lazy val git: Git = Git.open(build.moduleDir.toIO)

    /** Commit messages, newest first. */
    def messages: List[String] = git.log().call().asScala.toList.map(_.getFullMessage)

    def tags: List[String] = git.tagList().call().asScala.toList.map(_.getName.stripPrefix("refs/tags/"))

    /** The content of `path` in the tree `rev` points at. */
    def fileAt(rev: String, path: String): String =
      val repo = git.getRepository
      new String(repo.open(repo.resolve(s"$rev:$path")).getBytes, "UTF-8")

    def versionFile: String   = os.read(build.versionFile)
    def changelogFile: String = os.read(build.changelogFile)

  /**
   * Runs `command` on `build` over a copy of `repo`. The build's log is discarded rather than captured: Mill's prompt
   * logger hands it to the stream asynchronously, so nothing reliable can be asserted on it.
   */
  private def run[T](repo: Scripted, build: ReleaseRoot = ReleaseBuild())(
    command: ReleaseRoot => Task.Command[T]
  ): Outcome[T] =
    val quiet  = new PrintStream(OutputStream.nullOutputStream(), true, "UTF-8")
    val result = UnitTester(build, repo.dir, outStream = quiet, errStream = quiet).scoped(_(command(build)))
    Outcome(build, result)

  /**
   * The release commit under `tag` carries the release version and the changelog, and the commit after it the next
   * development version, which the version file holds too.
   */
  private def assertReleased(outcome: Outcome[Unit], tag: String, nextDev: String): Unit =
    val released = tag.stripPrefix(outcome.build.tagPrefix)
    outcome.value
    assert(outcome.versionFile == nextDev)
    assert(outcome.tags.contains(tag))
    assert(outcome.messages.take(2) == List(s"chore: set next development version $nextDev", s"chore: release $tag"))
    assert(outcome.fileAt(tag, "version") == released)
    assert(outcome.fileAt("HEAD", "version") == nextDev)
    assert(outcome.fileAt(tag, "CHANGELOG.md") == outcome.changelogFile)
    assert(outcome.changelogFile.startsWith(s"# Changelog\n\n## [$released] - $today\n"))

  /** Releasing `feat: a`, `breaking` and `chore: c` after `v1.2.3` bumps the major and lists `drop b` as breaking. */
  private def assertBreakingRelease(breaking: String): Unit =
    val outcome = run(taggedV123("feat: a", breaking, "chore: c"))(_.release())
    assertReleased(outcome, "v2.0.0", "2.0.1-SNAPSHOT")
    assert(outcome.changelogFile.contains("### Breaking Changes\n\n- drop b\n"))

  val tests = Tests:

    test("defaults - release is the default task, files sit at the workspace root, the repository is GitRepo's"):
      assert(ReleaseModule.defaultTask() == "release")
      assert(ReleaseModule.tagPrefix == "v")
      assert(ReleaseModule.typeMapping == ChangelogGenerator.DefaultTypeMapping)
      assert(ReleaseModule.versionFile == BuildCtx.workspaceRoot / "version")
      assert(ReleaseModule.changelogFile == BuildCtx.workspaceRoot / "CHANGELOG.md")
      assert(ReleaseModule.gitRepository eq GitRepo.repo)

    test("patch - bumps the patch of the latest version tag, ignoring tags without the prefix"):
      val outcome  = run(fixAndFeat.tag("foo").tag("release-9.9.9"))(_.patch())
      assertReleased(outcome, "v1.2.4", "1.2.5-SNAPSHOT")
      val expected =
        s"# Changelog\n\n## [1.2.4] - $today\n\n### Added\n\n- **core**: add thing\n\n### Fixed\n\n- correct a bug\n"
      assert(outcome.changelogFile == expected)

    test("minor - bumps the minor and resets the patch"):
      assertReleased(run(fixAndFeat)(_.minor()), "v1.3.0", "1.3.1-SNAPSHOT")

    test("major - bumps the major and resets the rest"):
      assertReleased(run(fixAndFeat)(_.major()), "v2.0.0", "2.0.1-SNAPSHOT")

    test("release - a breaking commit among feats and fixes bumps the major"):
      assertBreakingRelease("fix!: drop b")

    test("release - a BREAKING CHANGE footer bumps the major like the bang does"):
      assertBreakingRelease("fix: drop b\n\nExplains the change.\n\nBREAKING CHANGE: b is gone")

    test("release - a feat without breaking changes bumps the minor"):
      assertReleased(run(fixAndFeat)(_.release()), "v1.3.0", "1.3.1-SNAPSHOT")

    test("release - only fixes, chores and docs bump the patch"):
      val outcome = run(taggedV123("fix: a", "chore: b", "docs: c"))(_.release())
      assertReleased(outcome, "v1.2.4", "1.2.5-SNAPSHOT")
      assert(outcome.changelogFile.contains("### Other\n\n- c\n- b\n"))

    test("release - fails and leaves the repository untouched without unreleased conventional commits"):
      val outcome = run(taggedV123("not conventional", "WIP stuff"))(_.release())
      assert(outcome.failure == "No unreleased conventional commits found")
      assert(outcome.tags == List("v1.2.3"))
      assert(outcome.messages == List("WIP stuff", "not conventional", "chore: initial"))
      assert(!os.exists(outcome.build.versionFile))
      assert(!os.exists(outcome.build.changelogFile))

    test("latest tag - chosen by semantic version, not lexicographically or by creation order"):
      val repo = Scripted().commit("chore: initial").tag("v0.10.0").tag("v0.9.0").tag("v0.2.5").commit("fix: a")
      assertReleased(run(repo)(_.patch()), "v0.10.1", "0.10.2-SNAPSHOT")

    test("no version tag - the first release bumps 0.0.0 and lists every commit"):
      val outcome = run(Scripted().commit("feat: first").commit("fix: second"))(_.patch())
      assertReleased(outcome, "v0.0.1", "0.0.2-SNAPSHOT")
      assert(outcome.changelogFile.contains("- first"))
      assert(outcome.changelogFile.contains("- second"))

    test("unreleased - previews the commits after the latest tag under the version file's version, writing nothing"):
      val outcome = run(oldAndNew)(_.unreleased())
      assert(outcome.value == s"## [1.0.1-SNAPSHOT] - $today\n\n### Fixed\n\n- new\n")
      assert(!os.exists(outcome.build.changelogFile))
      assert(outcome.messages == List("fix: new", "feat: old"))

    test("changelog - writes the section for the version file's version above the existing changelog"):
      val existing = "# Changelog\n\n## [1.0.0] - 2026-01-01\n\n### Added\n\n- old\n"
      val outcome  = run(oldAndNew.changelog(existing))(_.changelog())
      val expected =
        s"# Changelog\n\n## [1.0.1-SNAPSHOT] - $today\n\n### Fixed\n\n- new\n\n## [1.0.0] - 2026-01-01\n\n### Added\n\n- old\n"
      assert(outcome.value == expected)
      assert(outcome.changelogFile == expected)
      assert(outcome.messages == List("fix: new", "feat: old"))

    test("tagPrefix - names the new tag, reads the last version from it and ignores tags carrying another prefix"):
      val repo    = Scripted().commit("chore: initial").tag("release-1.2.3").tag("v5.0.0").commit("fix: a")
      val outcome = run(repo, PrefixedBuild())(_.release())
      assertReleased(outcome, "release-1.2.4", "1.2.5-SNAPSHOT")
      assert(outcome.changelogFile == s"# Changelog\n\n## [1.2.4] - $today\n\n### Fixed\n\n- a\n")

    test("typeMapping - the changelog follows the module's mapping"):
      val repo    = Scripted().commit("chore: initial").tag("v1.0.0").commit("docs: explain").version("1.0.1-SNAPSHOT")
      val outcome = run(repo, RemappedBuild())(_.unreleased())
      assert(outcome.value == s"## [1.0.1-SNAPSHOT] - $today\n\n### Added\n\n- explain\n")

    test("gitRepository failure - releases fail with the cause and change nothing, the preview is empty"):
      val repo    = Scripted().commit("feat: a").version("0.1.0-SNAPSHOT")
      val failed  = run(repo, NoRepositoryBuild())(_.patch())
      assert(failed.failure == "Cannot open git repository: not a git repository")
      assert(failed.versionFile == "0.1.0-SNAPSHOT")
      assert(failed.tags.isEmpty)
      val preview = run(repo, NoRepositoryBuild())(_.unreleased())
      assert(preview.value == s"## [0.1.0-SNAPSHOT] - $today\n\n\n")

// --- Fixtures ---

// The root build is itself a ReleaseModule over the repository UnitTester copies into its module directory. Each
// test instantiates its fixture, so every UnitTester gets a fresh module directory (and repository copy).
abstract class ReleaseRoot extends TestRootModule with ReleaseModule:
  override def versionFile: os.Path                   = moduleDir / "version"
  override def changelogFile: os.Path                 = moduleDir / "CHANGELOG.md"
  override lazy val gitRepository: Result[Repository] = Result.Success(Git.open(moduleDir.toIO).getRepository)

class ReleaseBuild extends ReleaseRoot:
  lazy val millDiscover: Discover = Discover[this.type]

/** Tags carry the `release-` prefix instead of `v`. */
class PrefixedBuild extends ReleaseRoot:
  override def tagPrefix: String  = "release-"
  lazy val millDiscover: Discover = Discover[this.type]

/** `docs` commits are listed under Added. */
class RemappedBuild extends ReleaseRoot:
  override def typeMapping: Map[String, String] = ChangelogGenerator.DefaultTypeMapping + ("docs" -> "Added")
  lazy val millDiscover: Discover               = Discover[this.type]

/** The repository cannot be opened. */
class NoRepositoryBuild extends ReleaseRoot:
  override lazy val gitRepository: Result[Repository] = Result.Failure("not a git repository")
  lazy val millDiscover: Discover                     = Discover[this.type]

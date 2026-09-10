package atn.mill

import mill.*
import mill.api.{BuildCtx, DefaultTaskModule, Discover, ExternalModule, Result}

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Repository

import scala.jdk.CollectionConverters.*

/**
 * Changelog generation and release automation for conventional commits.
 *
 * Provides `patch`, `minor`, and `major` commands that automate the full release cycle:
 *   1. Bump version file to the release version
 *   2. Generate CHANGELOG.md from conventional commits since last tag
 *   3. Commit version + changelog, then tag
 *   4. Set next patch-SNAPSHOT development version and commit
 *
 * Also provides standalone `changelog` and `unreleased` commands for manual use.
 */
trait ReleaseModule extends DefaultTaskModule:

  /** Path to the version file. */
  def versionFile: os.Path = BuildCtx.workspaceRoot / "version"

  /** Path to the changelog file. */
  def changelogFile: os.Path = BuildCtx.workspaceRoot / "CHANGELOG.md"

  /** Git tag prefix. */
  def tagPrefix: String = "v"

  /** Conventional commit type to changelog section mapping. */
  def typeMapping: Map[String, String] = ChangelogGenerator.DefaultTypeMapping

  /** The git repository read and committed to: by default the one enclosing the working directory. */
  def gitRepository: mill.api.daemon.Result[Repository] = GitRepo.repo

  /**
   * Automatically release based on unreleased conventional commits.
   *
   * Breaking changes (`!` suffix or `BREAKING CHANGE` footer) trigger a major bump, `feat` commits trigger a minor
   * bump, and anything else (fix, chore, etc.) triggers a patch bump. Fails if there are no releasable commits.
   */
  def release() = Task.Command[Unit] {
    val commits = unreleasedCommits()
    if commits.isEmpty then throw new RuntimeException("No unreleased conventional commits found")
    val bump    = inferBump(commits)
    val result  = performRelease(bump)
    Task.log.info(result)
  }

  /** Perform a patch release (bump patch from last tag). */
  def patch() = Task.Command[Unit] {
    val result = performRelease("patch")
    Task.log.info(result)
  }

  /** Perform a minor release (bump minor from last tag). */
  def minor() = Task.Command[Unit] {
    val result = performRelease("minor")
    Task.log.info(result)
  }

  /** Perform a major release (bump major from last tag). */
  def major() = Task.Command[Unit] {
    val result = performRelease("major")
    Task.log.info(result)
  }

  /** Generate changelog for unreleased commits and write to CHANGELOG.md. */
  def changelog() = Task.Command[String] {
    val commits = unreleasedCommits()
    val version = currentVersion
    val content = writeChangelog(changelogSection(version, commits))
    Task.log.info(s"Wrote changelog for $version (${commits.size} commits)")
    content
  }

  /** Preview unreleased changelog without writing any file. */
  def unreleased() = Task.Command[String] {
    val section = changelogSection(currentVersion, unreleasedCommits())
    Task.log.info(section)
    section
  }

  override def defaultTask(): String = "release"

  /** The version file's content. */
  private def currentVersion: String = os.read(versionFile).trim

  /** The changelog section for `commits` under `version`, dated today. */
  private def changelogSection(version: String, commits: List[ConventionalCommit]): String =
    ChangelogGenerator.generate(version, java.time.LocalDate.now().toString, commits, typeMapping)

  /** Merge `section` into the changelog file and return its new content. */
  private def writeChangelog(section: String): String =
    val existing = Option.when(os.exists(changelogFile))(os.read(changelogFile))
    val content  = ChangelogGenerator.updateFile(existing, section)
    os.write.over(changelogFile, content)
    content

  /** Infer bump type from conventional commits: breaking → major, feat → minor, else → patch. */
  private def inferBump(commits: List[ConventionalCommit]): String =
    if commits.exists(_.breaking) then "major"
    else if commits.exists(_.typ == "feat") then "minor"
    else "patch"

  /**
   * Execute the full release cycle for the given bump type. Returns a summary string for logging.
   */
  private def performRelease(bump: String): String =
    val repo = gitRepository match
      case mill.api.daemon.Result.Success(r) => r
      case f: mill.api.daemon.Result.Failure =>
        throw new RuntimeException(s"Cannot open git repository: ${f.error}")
    val git  = new Git(repo)

    // 1. Determine release version from last tag
    val lastVersion = latestVersionTag(git)
      .flatMap(SemVer.parse)
      .getOrElse(SemVer(0, 0, 0))

    val releaseVersion = bump match
      case "patch" => lastVersion.bumpPatch
      case "minor" => lastVersion.bumpMinor
      case "major" => lastVersion.bumpMajor

    // 2. Write release version
    os.write.over(versionFile, releaseVersion.release.getBytes)

    // 3. Generate changelog
    val commits = unreleasedCommits()
    writeChangelog(changelogSection(releaseVersion.release, commits))

    // 4. Commit and tag
    val repoRoot         = repo.getWorkTree.toPath
    val relVersionFile   = repoRoot.relativize(versionFile.toNIO).toString
    val relChangelogFile = repoRoot.relativize(changelogFile.toNIO).toString

    git.add().addFilepattern(relVersionFile).addFilepattern(relChangelogFile).call()
    git.commit().setMessage(s"chore: release $tagPrefix${releaseVersion.release}").call()
    git.tag().setName(s"$tagPrefix${releaseVersion.release}").call()

    // 5. Set next development version (always next patch)
    val nextDev = releaseVersion.bumpPatch
    os.write.over(versionFile, nextDev.snapshot.getBytes)

    git.add().addFilepattern(relVersionFile).call()
    git.commit().setMessage(s"chore: set next development version ${nextDev.snapshot}").call()

    s"Released $tagPrefix${releaseVersion.release} (${commits.size} commits), next dev version ${nextDev.snapshot}"

  /** Collect parsed conventional commits since the last version tag. */
  private def unreleasedCommits(): List[ConventionalCommit] =
    gitRepository
      .map { repo =>
        val git     = new Git(repo)
        val head    = repo.resolve("HEAD")
        val lastTag = latestVersionTag(git)

        val logCmd = git.log()
        lastTag match
          case Some(tagRef) =>
            val tagId = repo.resolve(s"$tagRef^{commit}")
            logCmd.addRange(tagId, head)
          case None         =>
            logCmd.add(head)

        logCmd
          .call()
          .asScala
          .toList
          .flatMap(c => ConventionalCommit.parse(c.getName.take(7), c.getFullMessage))
      }
      .toOption
      .getOrElse(Nil)

  /** Find the most recent tag matching the version prefix, sorted by semver. */
  private def latestVersionTag(git: Git): Option[String] =
    git
      .tagList()
      .call()
      .asScala
      .map(_.getName.stripPrefix("refs/tags/"))
      .filter(_.startsWith(tagPrefix))
      .toList
      .sortBy { tag =>
        val parts = tag.stripPrefix(tagPrefix).split('.').map(_.toIntOption.getOrElse(0))
        (parts.lift(0).getOrElse(0), parts.lift(1).getOrElse(0), parts.lift(2).getOrElse(0))
      }
      .lastOption

object ReleaseModule extends ExternalModule with ReleaseModule:
  lazy val millDiscover: Discover = Discover[this.type]

package atn.mill

import mill.*
import mill.api.{BuildCtx, DefaultTaskModule, Discover, ExternalModule, Result}

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Repository

import scala.jdk.CollectionConverters.*

/**
 * Changelog generation plugin for conventional commits.
 *
 * Parses conventional commit messages since the last version tag and generates a Keep a Changelog formatted
 * CHANGELOG.md. Designed to complement Mill's built-in `VersionFileModule` which handles version bumping and tagging.
 *
 * Typical release workflow:
 * {{{
 * ./mill projectVersion.setNextVersion --bump minor
 * ./mill release.changelog
 * ./mill projectVersion.tag
 * }}}
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

  /** Generate changelog for unreleased commits and write to CHANGELOG.md. */
  def changelog() = Task.Command[String] {
    val commits  = unreleasedCommits()
    val version  = os.read(versionFile).trim
    val date     = java.time.LocalDate.now().toString
    val section  = ChangelogGenerator.generate(version, date, commits, typeMapping)
    val existing = Option.when(os.exists(changelogFile))(os.read(changelogFile))
    val content  = ChangelogGenerator.updateFile(existing, section)
    os.write.over(changelogFile, content)
    Task.log.info(s"Wrote changelog for $version (${commits.size} commits)")
    content
  }

  /** Preview unreleased changelog without writing any file. */
  def unreleased() = Task.Command[String] {
    val commits = unreleasedCommits()
    val version = os.read(versionFile).trim
    val date    = java.time.LocalDate.now().toString
    val section = ChangelogGenerator.generate(version, date, commits, typeMapping)
    Task.log.info(section)
    section
  }

  override def defaultTask(): String = "changelog"

  /** Collect parsed conventional commits since the last version tag. */
  private def unreleasedCommits(): List[ConventionalCommit] =
    GitRepo.repo
      .map { repo =>
        val git     = new Git(repo)
        val head    = repo.resolve("HEAD")
        val lastTag = latestVersionTag(git)

        val logCmd = git.log()
        lastTag match
          case Some(tagRef) =>
            val tagId = repo.resolve(tagRef)
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

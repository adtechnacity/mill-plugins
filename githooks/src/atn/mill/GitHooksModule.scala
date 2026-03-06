package atn.mill

import mill.*
import mill.api._
import mill.scalalib.scalafmt._

import mainargs.{arg, ArgSig, TokensReader}

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.RepositoryBuilder
import org.eclipse.jgit.revwalk.{RevCommit, RevWalk}
import org.eclipse.jgit.revwalk.filter.RevFilter

import scala.jdk.CollectionConverters._
import scala.util.Try

trait GitHooksModule extends DefaultTaskModule {

  /** Extra shell commands to run in the pre-commit hook before formatting checks. */
  def preCommitExtraCommands: Seq[String] = Seq.empty

  /** Email domain for co-author enrichment in commit preparation. Empty string disables. */
  def emailDomain: String = ""

  /** Optional regex pattern for Jira-style footer validation (e.g. `"Refs: [A-Z]+-\\d+"`). */
  def commitFooterPattern: Option[String] = None

  /** Ollama server URL for AI-assisted commit message generation. */
  def ollamaUrl: String = "http://localhost:11434"

  /** Ollama model name for commit message generation. */
  def ollamaModel: String = "qwen3:8b"

  /** Conventional commit types accepted by the validator. */
  def conventionalCommitTypes: List[String] = GitValidateCommit.DefaultTypes

  /** Module names to exclude from valid module resolution. */
  def excludedModuleNames: Set[String] = Set("test", "integration")

  def install(
    evaluator: Evaluator,
    @arg(
      name = "force",
      short = 'f',
      doc = "overwrites existing git hooks, even if they already exist"
    ) force: Boolean = false
  ) =
    Task.Command(exclusive = true)[WorkDone] {
      val ev = EvaluatorProxy(() => evaluator)
      new GitInstall(ev.rootModule.moduleDir / ".git/hooks", ev.baseLogger, preCommitExtraCommands)
        .install(force) match {
        case scala.util.Success(result) => result
        case scala.util.Failure(e)      => Result.Failure(e.getMessage)
      }
    }

  def preCommit() =
    ScalafmtModule.checkFormatAll()

  def prepCommit(evaluator: Evaluator, file: os.Path, source: String = "commit") =
    Task.Command(exclusive = true)[Unit] {
      val ev      = EvaluatorProxy(() => evaluator)
      val modules = validModules(ev.rootModule)
      val msg     = os.read(file)
      val gpc     = GitRepo.repo.map(new GitPrepCommit(_, modules.toList, ev.baseLogger, ollamaUrl, ollamaModel, emailDomain, commitFooterPattern))
      gpc.map(gpc => os.write.over(file, gpc.prep(msg, source)))
    }

  def validateCommit(evaluator: Evaluator, file: os.Path) =
    Task.Command(exclusive = true)[Unit] {
      val ev        = EvaluatorProxy(() => evaluator)
      val modules   = validModules(ev.rootModule)
      val validator = GitRepo.repo.map(new GitValidateCommit(_, conventionalCommitTypes, modules, ev.baseLogger))
      val msg       = os.read(file)
      validator.flatMap(_.validate(msg))
    }

  /** Task selectors resolved by prePush to run tests before pushing. */
  def prePushTasks: Seq[String] = Seq("__.test")

  def prePush(evaluator: Evaluator) =
    Task.Command(exclusive = true)[Unit] {
      val ev = EvaluatorProxy(() => evaluator)
      ev.resolveTasks(prePushTasks, SelectMode.Multi) match {
        case f: Result.Failure     => f
        case Result.Success(tasks) =>
          ev.execute(tasks.asInstanceOf[Seq[Task[Any]]]).values match {
            case f: Result.Failure => Result.Failure(s"Tests failed: ${f.error}")
            case _                 => ()
          }
      }
    }

  def validModules(rootModule: Module): Set[String] = {
    def more(module: Module): Seq[String] =
      module.moduleDirectChildren
        .filter(_.moduleSegments.parts.nonEmpty)
        .map(_.moduleSegments.last.value)
        .filter(m => m.head == m.head.toLower)
        .filterNot(excludedModuleNames.contains) ++
        module.moduleDirectChildren.flatMap(more)
    more(rootModule)
      .appended("mill-build")
      .toSet
  }

  /** Delegate to core GitRepo for head branch. */
  def headBranch() = GitRepo.headBranch()

  /** Delegate to core GitRepo for head SHA. */
  def headSHA() = GitRepo.headSHA()

  /** Delegate to core GitRepo for head tag. */
  def headTag() = GitRepo.headTag()

}

object GitHooksModule extends ExternalModule with GitHooksModule {
  override def defaultTask(): String = "install"

  lazy val millDiscover: Discover = Discover[this.type]
}

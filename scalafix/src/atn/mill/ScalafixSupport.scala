package atn.mill

import com.goyeau.mill.scalafix.{ScalafixModule => UpstreamScalafixModule}
import mill.*
import mill.api.{BuildCtx, Evaluator, Result, Task}
import mill.scalalib.*

trait ScalafixSupport extends ScalaModule:

  def scalafix(evaluator: Evaluator) = Task.Command(exclusive = true)[Unit] {
    val sourceFiles = UpstreamScalafixModule.filesToFix(allSources())
    val semanticCp  = compiledClassesAndSemanticDbFiles().path
    val cp          = compileClasspath().map(_.path) ++ Seq(semanticCp)
    val cfg         = BuildCtx.workspaceRoot / ".scalafix.conf"

    UpstreamScalafixModule.fixAction(
      Task.log,
      repositoriesTask(),
      sourceFiles.map(_.path),
      cp,
      scalaVersion(),
      scalacOptions(),
      Seq.empty[Dep],
      Option.when(os.exists(cfg))(cfg),
      Seq.empty[String],
      BuildCtx.workspaceRoot
    ) match
      case r: Result.Failure => Result.Failure(r.error)
      case _                 => Result.Success(())
  }

  def scalafixCheck(evaluator: Evaluator) = Task.Command(exclusive = true)[Unit] {
    val sourceFiles = UpstreamScalafixModule.filesToFix(allSources())
    val semanticCp  = compiledClassesAndSemanticDbFiles().path
    val cp          = compileClasspath().map(_.path) ++ Seq(semanticCp)
    val cfg         = BuildCtx.workspaceRoot / ".scalafix.conf"

    UpstreamScalafixModule.fixAction(
      Task.log,
      repositoriesTask(),
      sourceFiles.map(_.path),
      cp,
      scalaVersion(),
      scalacOptions(),
      Seq.empty[Dep],
      Option.when(os.exists(cfg))(cfg),
      Seq("--check"),
      BuildCtx.workspaceRoot
    ) match
      case r: Result.Failure => Result.Failure(r.error)
      case _                 => Result.Success(())
  }

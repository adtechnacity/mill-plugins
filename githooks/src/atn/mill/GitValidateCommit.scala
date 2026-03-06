package atn.mill

import mill.api.{Logger, Result}

import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.api.Git

import GitValidateCommit._

class GitValidateCommit(repo: Repository, types: List[String], modules: Set[String], log: Logger) {

  def this(repo: Repository, modules: Set[String], log: Logger) = this(repo, DefaultTypes, modules, log)

  log.info(s"accepting types: ${types.mkString(",")}")
  log.info(s"accepting modules: ${modules.mkString(",")}")

  def validate(msg: String): Result[Unit] = {
    val lines = msg.split("\n").filterNot(_.startsWith("#"))
    lines.headOption match {
      case Some(header @ FirstLineRE(typ, mod, brk, msg)) =>
        val errors =
          (checkType(typ) ::
            checkModule(mod) ::
            checkBreaking(brk) ::
            checkMessage(msg.length())).filterNot(_._1)

        if (errors.isEmpty) Result.Success(())
        else Result.Failure(s"Illegal conventional commit.\n* ${errors.map(_._2).mkString(",\n* ")}\n$header")
      case _                                              => Result.Failure(s"Could not decode conventional commit from:\n$msg")
    }
  }

  def checkType(typ: String): GitValidation =
    types.contains(typ) -> s"$typ is not a valid type"

  def checkModule(mod: String): GitValidation =
    Option(mod).forall(modules.contains) -> s"$mod is not a valid module"

  // TODO: decide if we're validating version bumps here
  def checkBreaking(breaking: String): GitValidation =
    true -> "breaking commits not checked"

  def checkMessage(len: Int): List[GitValidation] =
    ((len > MinHeaderLength)   -> "Message too short") ::
      ((len < MaxHeaderLength) -> "Message too long") ::
      Nil

}

object GitValidateCommit {
  type GitValidation = (Boolean, String)
  val FirstLineRE     = """^(\w+)(?:\(([\w\s,-]+)\))?(!)?:\s(.+)$""".r
  val MinHeaderLength = 12
  val MaxHeaderLength = 70
  val DefaultTypes    =
    "chore" :: "ci" :: "docs" :: "feat" :: "fix" :: "refactor" :: "style" :: Nil

}

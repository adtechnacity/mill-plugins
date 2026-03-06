package atn.mill

import mill.api.Logger

import fallatol.ollama._
import fallatol.ollama.client._
import sttp.client4._
import sttp.model.Uri

import org.eclipse.jgit.lib.{IndexDiff, Repository}
import org.eclipse.jgit.treewalk.FileTreeIterator
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.api.Git

import scala.jdk.CollectionConverters._

import java.io.ByteArrayOutputStream
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.util.io.NullOutputStream

import scala.util.Try

class GitPrepCommit(
  repo: Repository,
  scopes: List[String],
  log: Logger,
  ollamaUrl: String = "http://localhost:11434",
  ollamaModel: String = "qwen3:8b",
  emailDomain: String = "",
  commitFooterPattern: Option[String] = None
) {

  val thoughtRE        = "<think>([\\s\\S]*)</think>([\\s\\S]*)".r
  val illegalHeadingRE = "^\\w+:\\s*$".r

  val conventionalCommitsDescription =
    s"""
       |We use conventional commits (https://www.conventionalcommits.org/)
       |   ```<type>([scope]): <description>
       |
       |      [body]
       |
       |      [footer(s)]
       |   ```
       |
       |Valid types are: "${GitValidateCommit.DefaultTypes.mkString("\", \"")}"
       |Valid scopes are: "${scopes.mkString("\", \"")}"
       |Additionally, scopes, although optional, are recommended and should be the
       |most relevant *mill module*.
       |
       |In the body please write some lines further describing the change.""".stripMargin

  val git = new Git(repo)

  def ollamaUri = Option(System.getenv("OLLAMA_URL")).filter(_.nonEmpty).getOrElse(ollamaUrl)

  def ollama = new SyncOllamaClient(Uri.unsafeParse(ollamaUri), DefaultSyncBackend())

  def needlessMessageStart(c: Char): Boolean =
    c == '\n' || c == '\r' || c == ' '

  def comment(str: String): String =
    "#" + str.dropWhile(needlessMessageStart).replace("\n", "\n#")

  def messageTemplate(entries: Seq[DiffEntry]): (String, String) = {
    val changes    = stagedChanges(entries)
    val modelToUse = Option(System.getenv("OLLAMA_MODEL")).filter(_.nonEmpty).getOrElse(ollamaModel)
    Try(
      ollama
        .chat(
          ChatRequest(
            Model.Custom(modelToUse),
            Seq(
              Message.User(s"Here is the git diff of what we will commit: \n$changes\n---"),
              Message.System(
                s"You should select the scope from this list:\n${entries.map(_.getNewPath().takeWhile(_ != '/')).mkString(",")}\n---"
              ),
              Message.System("Avoid any ``text formatting or Body: and Footer: headers in your reply"),
              Message.User(s"""Create a conventional commit git commit message.
                              | Use the supplied git diff and previously used message to generate a clear
                              | header line using the most probable type and scope given the files modified
                              | above.
                              | Remember $conventionalCommitsDescription
                              | Alongside the header, you should summarize the changes in just a few bullet
                              | points. Keep your response within the structure of a conventional commit.""".stripMargin)
            )
          )
        )
    ).toEither.flatten
      .map { resp =>
        val msg                  = resp.message.content
        val (thoughts, template) = thoughtRE.findAllIn(msg).matchData.toList.foldLeft(("", "")) { case ((t, s), m) =>
          (t + m.group(1), s + m.group(2))
        }
        log.debug(s"llm response duration: ${resp.totalDuration.getOrElse("unknown")}")
        log.info(s"model \"thoughts\":$thoughts")
        comment(thoughts) -> comment(illegalHeadingRE.replaceAllIn(template, ""))
      }
      .left
      .map(e => println(s"Failed to chat with ollama. ${e.getMessage}"))
      .getOrElse("" -> "")
  }

  def stagedChanges(entries: Seq[DiffEntry]): String = {
    val os     = new ByteArrayOutputStream()
    val format = new DiffFormatter(os)
    format.setRepository(repo)
    log.info(s"staged files processed: ${entries.map(_.getNewPath()).mkString(",")}")
    format.format(entries.asJava)
    format.flush()
    os.toString()
  }

  def prep(prevMsg: String, source: String): String = source match {

    /** only generate for commit messages */
    case "commit" => gen(prevMsg)

    /** message / template / merge / squash are all passed through */
    case _ => prevMsg
  }

  private def footerInstructions: String =
    commitFooterPattern match {
      case Some(pattern) =>
        s"""|#In the footer:
            |#  you should specify the jira ticket with a line like: `Refs: PRJ-123`.
            |#  document co-authors via `Co-Authored-By: Them <Them@${if (emailDomain.nonEmpty) emailDomain else "example.com"}>` lines.
            |#  document reviewers via `Reviewed-By: Me <me@${if (emailDomain.nonEmpty) emailDomain else "example.com"}>` lines.""".stripMargin
      case None          =>
        """|#In the footer:
           |#  document co-authors via `Co-Authored-By:` lines.""".stripMargin
    }

  def gen(prevMsg: String): String = {
    val msg                    = prevMsg.dropWhile(needlessMessageStart).split("\n")
    val entries                = git.diff().setOutputStream(NullOutputStream.INSTANCE).setCached(true).call().asScala.toVector
    val (thoughts, suggestion) = if (entries.isEmpty) "" -> "" else messageTemplate(entries)
    s"""$suggestion
       |#
       |${msg.takeWhile(!_.startsWith("#")).mkString("\n")}
       |${comment(conventionalCommitsDescription)}
       |#
       |$footerInstructions
       |${msg.dropWhile(_.startsWith("#")).mkString("\n")}""".stripMargin
  }

}

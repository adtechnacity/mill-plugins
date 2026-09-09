package atn.mill

import mill.api.daemon.Logger.DummyLogger
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffEntry
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Prop.forAll
import utest.*

import scala.jdk.CollectionConverters.*

import FakeOllama.{chatReply, serving}
import GitFixtures.{repoWithCommit, stage, workTree}

object GitPrepCommitTest extends TestSuite:

  private val scopes = List("core", "app")

  /** What the fork environment's OLLAMA_MODEL names; it wins over the configured model (see build.mill). */
  private val envModel = "test-model-from-env"

  /** A closed port: any attempt to chat fails at once. */
  private val noServer = "http://127.0.0.1:1"

  private val llmReply = "<think>weighing the diff</think>\nfeat(core): add the greeting\n\n- says hello"

  private val footerPattern = Some("Refs: [A-Z]+-\\d+")

  private def prepCommit(
    git: Git,
    ollamaUrl: String = noServer,
    emailDomain: String = "",
    footer: Option[String] = None
  ): GitPrepCommit =
    new GitPrepCommit(git.getRepository, scopes, DummyLogger, ollamaUrl, "qwen3-test", emailDomain, footer)

  /** One commit, `core/src/Hello.scala` staged on top, and an unstaged edit to README that must not be picked up. */
  private def stagedRepo(): Git =
    val git = repoWithCommit()
    stage(git, "core/src/Hello.scala", "object Hello\n")
    os.write.over(workTree(git) / "README", "edited but not staged\n")
    git

  private def stagedEntries(git: Git): Seq[DiffEntry] =
    git.diff().setCached(true).setShowNameAndStatusOnly(true).call().asScala.toSeq

  private def roleAndContent(request: ujson.Value): Seq[(String, String)] =
    request("messages").arr.toSeq.map(m => (m("role").str, m("content").str))

  private val coAuthorsOnly = "#In the footer:\n#  document co-authors via `Co-Authored-By:` lines."

  val tests = Tests:

    test("needlessMessageStart - newlines, carriage returns and spaces, nothing else") {
      val gpc = prepCommit(repoWithCommit())
      assert(gpc.needlessMessageStart('\n'))
      assert(gpc.needlessMessageStart('\r'))
      assert(gpc.needlessMessageStart(' '))
      assert(!gpc.needlessMessageStart('\t'))
      assert(!gpc.needlessMessageStart('#'))
      Props.holds(forAll(Arbitrary.arbitrary[Char])(c => gpc.needlessMessageStart(c) == "\n\r ".contains(c)))
    }

    test("comment - drops the leading blank run and prefixes every line with #") {
      val gpc = prepCommit(repoWithCommit())
      assert(gpc.comment("\n\r  first\nsecond\n") == "#first\n#second\n#")
      assert(gpc.comment("") == "#")
    }

    test("ollamaUri - the configured URL once the environment names none; the default is the local server") {
      assert(prepCommit(repoWithCommit(), noServer).ollamaUri == noServer)
      assert(new GitPrepCommit(repoWithCommit().getRepository, Nil, DummyLogger).ollamaUri == "http://localhost:11434")
    }

    test("prep - only the commit source generates; every other source passes the message through") {
      val gpc       = prepCommit(repoWithCommit())
      val genSource =
        Gen.oneOf(Gen.oneOf("message", "template", "merge", "squash"), Gen.alphaStr).suchThat(_ != "commit")
      Props.holds(forAll(genSource, Gen.asciiPrintableStr)((source, msg) => gpc.prep(msg, source) == msg))
      assert(gpc.prep("feat: draft", "commit") == gpc.gen("feat: draft"))
    }

    test("stagedChanges - a unified diff of the staged entries only") {
      val git  = stagedRepo()
      val diff = prepCommit(git).stagedChanges(stagedEntries(git))
      assert(diff.contains("diff --git a/core/src/Hello.scala b/core/src/Hello.scala"))
      assert(diff.contains("+object Hello"))
      assert(!diff.contains("edited but not staged"))
    }

    test("gen - nothing staged: no model is consulted and the template wraps the message") {
      serving(200, chatReply(llmReply)) { fake =>
        val out = prepCommit(repoWithCommit(), fake.url).gen("feat: draft\n# trailing comment")
        assert(fake.chatRequests.isEmpty)
        assert(out.startsWith("\n#\nfeat: draft\n#We use conventional commits (https://www.conventionalcommits.org/)\n"))
        assert(out.contains("\n#Valid scopes are: \"core\", \"app\"\n"))
        assert(
          out.contains("\n#Valid types are: \"chore\", \"ci\", \"docs\", \"feat\", \"fix\", \"refactor\", \"style\"\n")
        )
        assert(out.endsWith(s"\n#\n$coAuthorsOnly\nfeat: draft\n# trailing comment"))
      }
    }

    test("gen - leading blank lines and git's own comment block are dropped from the message") {
      val out = prepCommit(repoWithCommit()).gen("\n\n# Please enter the commit message\n#\n\nkept line")
      assert(out.startsWith("\n#\n\n#We use conventional commits"))
      assert(out.endsWith(s"$coAuthorsOnly\n\nkept line"))
    }

    test("gen - staged changes: the model's reply leads the message as comments") {
      serving(200, chatReply(llmReply)) { fake =>
        val out = prepCommit(stagedRepo(), fake.url).gen("\n# git template\n")
        assert(fake.chatRequests.size == 1)
        assert(out.startsWith("#feat(core): add the greeting\n#\n#- says hello\n#\n\n#We use conventional commits"))
        assert(!out.contains("weighing the diff"))
      }
    }

    test("messageTemplate - sends the staged diff, the top-level scopes of the staged paths and the environment's model") {
      serving(200, chatReply(llmReply)) { fake =>
        val git      = stagedRepo()
        val template = prepCommit(git, fake.url).messageTemplate(stagedEntries(git))
        assert(template == ("#weighing the diff", "#feat(core): add the greeting\n#\n#- says hello"))

        val request  = fake.chatRequests.head
        assert(request("model").str == envModel)
        assert(!request("stream").bool)
        val messages = roleAndContent(request)
        assert(messages.size == 4)
        assert(messages(0)._1 == "user")
        assert(
          messages(0)._2.startsWith("Here is the git diff of what we will commit: \ndiff --git a/core/src/Hello.scala")
        )
        assert(messages(0)._2.contains("+object Hello"))
        assert(messages(1) == ("system", "You should select the scope from this list:\ncore\n---"))
        assert(messages(2)._1 == "system")
        assert(messages(3)._1 == "user")
        assert(messages(3)._2.contains("Valid scopes are: \"core\", \"app\""))
      }
    }

    test("messageTemplate - a failing server yields no suggestion at all") {
      serving(500, "model exploded") { fake =>
        val git = stagedRepo()
        assert(prepCommit(git, fake.url).messageTemplate(stagedEntries(git)) == ("", ""))
        assert(fake.chatRequests.size == 1)
      }
    }

    test("messageTemplate - an unreachable server yields no suggestion at all") {
      val git = stagedRepo()
      assert(prepCommit(git).messageTemplate(stagedEntries(git)) == ("", ""))
    }

    test("footer - a footer pattern asks for the ticket, co-authors and reviewers at example.com by default") {
      val out = prepCommit(repoWithCommit(), footer = footerPattern).gen("feat: draft")
      assert(out.contains("#  you should specify the jira ticket with a line like: `Refs: PRJ-123`.\n"))
      assert(out.contains("#  document co-authors via `Co-Authored-By: Them <Them@example.com>` lines.\n"))
      assert(out.contains("#  document reviewers via `Reviewed-By: Me <me@example.com>` lines."))
    }

    test("footer - the configured email domain replaces example.com") {
      val out = prepCommit(repoWithCommit(), emailDomain = "acme.io", footer = footerPattern).gen("feat: draft")
      assert(out.contains("`Co-Authored-By: Them <Them@acme.io>`"))
      assert(out.contains("`Reviewed-By: Me <me@acme.io>`"))
      assert(!out.contains("example.com"))
    }

    test("footer - without a footer pattern only co-authors are requested, whatever the domain") {
      val out = prepCommit(repoWithCommit(), emailDomain = "acme.io").gen("feat: draft")
      assert(out.contains(coAuthorsOnly))
      assert(!out.contains("Refs:"))
      assert(!out.contains("acme.io"))
    }

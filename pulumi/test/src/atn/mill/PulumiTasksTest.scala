package atn.mill

import mill.*
import mill.api.{Discover, ExecResult, PathRef}
import mill.javalib.Assembly
import mill.testkit.{TestRootModule, UnitTester}
import utest.*

import com.pulumi.automation.{CommandResult, CommandRunOptions, LocalWorkspaceOptions, PulumiCommand, Version}
import com.pulumi.automation.events.{EngineEvent, SummaryEvent}

import java.io.{ByteArrayOutputStream, PrintStream}
import scala.jdk.CollectionConverters.*

/**
 * Drives the tasks of [[PulumiModule]] through `UnitTester` without the pulumi binary: the Automation API is handed
 * [[FakePulumi]] in place of the CLI, and `pulumi package gen-sdk` is a shell script writing what gen-sdk would.
 */
object PulumiTasksTest extends TestSuite:

  /** The value `task` evaluates to under `eval`. */
  private def ran[T](eval: UnitTester)(task: Task[T]): T = eval(task) match
    case Right(ok)  => ok.value
    case Left(fail) => sys.error(s"task failed: $fail")

  /** The message of the exception `task` throws under `eval`. */
  private def failed[T](eval: UnitTester)(task: Task[T]): String = eval(task) match
    case Left(ExecResult.Exception(thrown, _)) => thrown.getMessage
    case other                                 => sys.error(s"expected the task to throw, got $other")

  /**
   * A workspace whose `infra/fake-pulumi` stands in for the CLI's `package gen-sdk`: it logs its arguments to
   * `$FAKE_PULUMI_LOG` and writes the gradle files under `--out`, naming the project with `key` (only
   * `rootProject.name` is what gen-sdk writes, anything else leaves the SDK unidentifiable).
   */
  private def workspace(key: String = "rootProject.name"): os.Path =
    val root   = os.temp.dir()
    val script = root / "infra" / "fake-pulumi"
    os.write(
      script,
      s"""#!/bin/sh
         |printf '%s\\n' "$$*" >> "$$FAKE_PULUMI_LOG"
         |while [ "$$#" -gt 1 ]; do [ "$$1" = "--out" ] && out="$$2"; shift; done
         |mkdir -p "$$out/java"
         |printf '$key = "com.pulumi.%s"\\n' "$$(basename "$$out")" > "$$out/java/settings.gradle"
         |printf '%s\\n' '(project.version == "unspecified" ? "0.3.1" : project.version)' > "$$out/java/build.gradle"
         |""".stripMargin,
      createFolders = true
    )
    os.perms.set(script, "rwxr-xr-x")
    root

  private def genSdkCalls(build: ConfiguredBuild): Seq[String] = os.read.lines(build.infra.moduleDir / "gen-sdk.log")

  private def cliCalls(build: ConfiguredBuild): Seq[String] = os.read.lines(build.infra.moduleDir / "pulumi.log")

  private def zetaUrl = "https://github.com/acme/zeta"

  /** `body` with the process stderr, which the module hands the CLI's error stream to, redirected into `sink`. */
  private def withSystemErr[T](sink: ByteArrayOutputStream)(body: => T): T =
    val original = System.err
    System.setErr(new PrintStream(sink, true))
    try body
    finally System.setErr(original)

  /**
   * The configured local packages as the module resolves them: relative sources against the tester's workspace root.
   */
  private def localPackages(build: ConfiguredBuild) =
    Map("acme" -> (build.moduleDir / "vendor" / "acme").toString, "zeta" -> zetaUrl)

  val tests = Tests:

    test("defaults - the documented values, the services-merging assembly rule and no shell prelude") {
      val infra = new DefaultBuild().infra
      assert(infra.pulumiSdkVersion == "1.13.2")
      assert(infra.pulumiProjectName == "infra")
      assert(infra.pulumiStack == "dev")
      assert(infra.pulumiCli == "pulumi")
      assert(infra.pulumiEnv.isEmpty)
      assert(infra.pulumiLocalPackages.isEmpty)
      assert(infra.pulumiProjectDir == infra.moduleDir)
      assert(infra.workspaceOptions.build().pulumiCommand() == null) // the Automation API then uses the CLI on PATH
      // The operations drive an interactive CLI, so none of them may run alongside other tasks.
      assert(Seq(infra.preview(), infra.up(), infra.refresh(), infra.destroy()).forall(_.exclusive))
      val services = infra.assemblyRules.collect { case r: Assembly.Rule.AppendPattern =>
        r.pattern.pattern -> r.separator
      }
      assert(services == Seq("META-INF/services/.*" -> "\n"))
    }

    test("dependencies - the SDK at pulumiSdkVersion, jsr305 only with local packages, runtime deps on the compile classpath") {
      val plain      = new DefaultBuild
      UnitTester(plain, workspace()).scoped { eval =>
        assert(
          ran(eval)(plain.infra.mvnDeps).map(_.toString).exists(d => d.contains("com.pulumi") && d.contains("1.13.2"))
        )
        assert(!ran(eval)(plain.infra.compileMvnDeps).exists(_.toString.contains("jsr305")))
        assert(ran(eval)(plain.infra.prependShellScript) == "")
        val names = ran(eval)(plain.infra.compileClasspath).map(_.path.last)
        assert(names.contains("pulumi-1.13.2.jar"))
        assert(names.exists(_.startsWith("protobuf-java-")))
      }
      val configured = new ConfiguredBuild
      UnitTester(configured, workspace()).scoped { eval =>
        val deps = ran(eval)(configured.infra.mvnDeps).map(_.toString)
        assert(deps.exists(d => d.contains("com.pulumi") && d.contains("1.10.0")))
        assert(ran(eval)(configured.infra.compileMvnDeps).exists(_.toString.contains("jsr305")))
      }
    }

    test("pulumiPackageSdks - one gen-sdk per local package in name order, sources resolved, SDK resources written") {
      val build = new ConfiguredBuild
      UnitTester(build, workspace()).scoped { eval =>
        val sdks          = ran(eval)(build.infra.pulumiPackageSdks).map(_.path)
        val dest          = build.moduleDir / "out" / "infra" / "pulumiPackageSdks.dest"
        assert(sdks == Seq(dest / "acme" / "java" / "src" / "main", dest / "zeta" / "java" / "src" / "main"))
        val expectedCalls = Seq(
          s"package gen-sdk ${localPackages(build)("acme")} --language java --out ${dest / "acme"}",
          s"package gen-sdk $zetaUrl --language java --out ${dest / "zeta"}"
        )
        assert(genSdkCalls(build) == expectedCalls)
        val acme          = sdks.head / "resources" / "com" / "pulumi" / "acme"
        assert(os.read(acme / "version.txt") == "0.3.1")
        val info          = PulumiModule.SdkPackageInfo("com/pulumi/acme", "acme", "0.3.1")
        assert(os.read(acme / "plugin.json") == PulumiModule.pluginJson(info))
        assert(ran(eval)(build.infra.generatedSources).map(_.path).containsSlice(sdks.map(_ / "java")))
        assert(ran(eval)(build.infra.resources).map(_.path).containsSlice(sdks.map(_ / "resources")))
      }
    }

    test("pulumiPackageSdks - a generated SDK without a readable identity fails the task") {
      val build = new ConfiguredBuild
      UnitTester(build, workspace(key = "nothing")).scoped { eval =>
        val message = failed(eval)(build.infra.pulumiPackageSdks)
        assert(message == "Could not read package name and version of generated SDK acme")
      }
    }

    test("preview - writes Pulumi.yaml for the assembly and packages, then previews the stack through the build's stdio") {
      val build = new ConfiguredBuild
      val out   = new ByteArrayOutputStream
      val err   = new ByteArrayOutputStream
      UnitTester(build, workspace(), outStream = new PrintStream(out)).scoped { eval =>
        withSystemErr(err)(ran(eval)(build.infra.preview("staging")))
        val jar   = ran(eval)(build.infra.assembly).path
        assert(
          os.read(build.infra.moduleDir / "Pulumi.yaml") == PulumiModule
            .projectYaml("acme-infra", jar, localPackages(build))
        )
        val calls = cliCalls(build)
        assert(calls.map(_.takeWhile(_ != ' ')) == Seq("stack", "preview"))
        assert(calls.head.startsWith("stack select --stack staging "))
        assert(calls.forall(_.endsWith(s" [FAKE_PULUMI_LOG=${build.infra.moduleDir / "gen-sdk.log"}]")))
      }
      assert(out.toString.linesIterator.exists(_.startsWith("pulumi> preview ")))
      assert(err.toString.linesIterator.exists(_.startsWith("pulumi! preview ")))
    }

    test("every operation - runs its verb against the configured default stack") {
      val build = new ConfiguredBuild
      UnitTester(build, workspace()).scoped { eval =>
        ran(eval)(build.infra.preview())
        ran(eval)(build.infra.up())
        ran(eval)(build.infra.refresh())
        ran(eval)(build.infra.destroy())
        val calls = cliCalls(build)
        assert(calls.count(_.startsWith("stack select --stack prod ")) == 4)
        assert(calls.map(_.takeWhile(_ != ' ')).filterNot(_ == "stack") == Seq("preview", "up", "refresh", "destroy"))
      }
    }

/**
 * The pulumi CLI as the Automation API sees it: logs every invocation (with the forwarded `FAKE_PULUMI_LOG`), echoes to
 * each attached stream, ends every operation with an empty summary and reports one finished update as its history, so
 * the API considers each operation complete.
 */
final class FakePulumi(log: os.Path) extends PulumiCommand:
  def version(): Version = Version.of(3, 200, 0)

  def run(args: java.util.List[String], options: CommandRunOptions): CommandResult =
    val command = args.asScala.mkString(" ")
    val env     = Option(options.additionalEnv()).map(_.asScala.getOrElse("FAKE_PULUMI_LOG", "unset")).getOrElse("unset")
    os.write.append(log, s"$command [FAKE_PULUMI_LOG=$env]\n", createFolders = true)
    Option(options.onStandardOutput()).foreach(_.accept(s"pulumi> $command"))
    Option(options.onStandardError()).foreach(_.accept(s"pulumi! $command"))
    Option(options.onEngineEvent()).foreach(_.accept(FakePulumi.summary))
    new CommandResult(0, FakePulumi.stdout(args.asScala.toList), "")

object FakePulumi:
  /**
   * One finished update, as `pulumi stack history --json` reports it: the API reads the operation's summary from it.
   */
  private val history =
    """[{"kind": "update", "startTime": "2026-01-01T00:00:00.000Z", "message": "", "environment": {}, "config": {},
      |  "result": "succeeded", "endTime": "2026-01-01T00:00:01.000Z", "version": 1, "resourceChanges": {"create": 1}}]
      |""".stripMargin

  private val summary =
    new EngineEvent(
      1,
      0,
      null,
      null,
      null,
      null,
      new SummaryEvent(false, 0, java.util.Map.of(), java.util.Map.of()),
      null,
      null,
      null,
      null
    )

  private def stdout(args: List[String]): String = args match
    case "stack" :: "output" :: _  => "{}"
    case "stack" :: "history" :: _ => history
    case _                         => ""

/** A [[PulumiModule]] whose program jar is never built: the tasks under test only write its path into Pulumi.yaml. */
trait StubbedPulumiModule extends PulumiModule:
  def scalaVersion = "3.8.4"

  override def assembly = Task {
    val jar = Task.dest / "out.jar"
    os.write(jar, "")
    PathRef(jar)
  }

// Nested in classes rather than objects: Mill's reflection does not see objects nested in an object under Scala 3.
abstract class DefaultPulumiRoot extends TestRootModule:
  object infra extends StubbedPulumiModule

abstract class ConfiguredPulumiRoot extends TestRootModule:
  object infra extends StubbedPulumiModule:
    override def pulumiSdkVersion    = "1.10.0"
    override def pulumiProjectName   = "acme-infra"
    override def pulumiStack         = "prod"
    override def pulumiCli           = (moduleDir / "fake-pulumi").toString
    override def pulumiEnv           = Map("FAKE_PULUMI_LOG" -> (moduleDir / "gen-sdk.log").toString)
    override def pulumiLocalPackages = Map("zeta" -> "https://github.com/acme/zeta", "acme" -> "vendor/acme")

    override private[mill] def workspaceOptions =
      LocalWorkspaceOptions.builder().pulumiCommand(FakePulumi(moduleDir / "pulumi.log"))

class DefaultBuild extends DefaultPulumiRoot:
  lazy val millDiscover: Discover = Discover[this.type]

class ConfiguredBuild extends ConfiguredPulumiRoot:
  lazy val millDiscover: Discover = Discover[this.type]

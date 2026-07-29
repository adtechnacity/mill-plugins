package atn.mill

import mill.*
import mill.api.{BuildCtx, PathRef, Task}
import mill.javalib.Assembly
import mill.scalalib.*

import com.pulumi.automation.{
  DestroyOptions,
  LocalWorkspace,
  LocalWorkspaceOptions,
  PreviewOptions,
  RefreshOptions,
  UpOptions,
  WorkspaceStack
}

import scala.jdk.CollectionConverters.*
import scala.util.Using

/**
 * Pulumi operations for an infrastructure program written in Scala with the com.pulumi Java SDK.
 *
 * The module compiles the program to an assembly jar and generates a Pulumi.yaml with a java runtime pointing at that
 * jar. Operations run through the Pulumi Automation API (which drives the pulumi CLI, the only external requirement),
 * so `./mill pulumi.preview` and `./mill pulumi.up` work out of the box. The module directory is the Pulumi project
 * directory: stack config files (Pulumi.<stack>.yaml) live there, checked into the repo. The generated Pulumi.yaml
 * should be gitignored.
 *
 * Local packages that are not in the registry (e.g. a checkout of a component provider) are declared via
 * [[pulumiLocalPackages]]: each gets a Java SDK generated with pulumi package gen-sdk and compiled into this module,
 * plus a packages entry in Pulumi.yaml so the engine loads the plugin from the local source at runtime.
 */
trait PulumiModule extends ScalaModule {

  /** Version of the com.pulumi:pulumi Java SDK added to this module's dependencies. */
  def pulumiSdkVersion: String = "1.13.2"

  /** Pulumi project name written to the generated Pulumi.yaml. */
  def pulumiProjectName: String = moduleSegments.render

  /** Stack operated on when no --stack argument is given. Created on first use if missing. */
  def pulumiStack: String = "dev"

  /** Pulumi CLI executable, only used for SDK generation of local packages; a name on PATH or an absolute path. */
  def pulumiCli: String = "pulumi"

  /** Extra environment variables passed to every pulumi invocation. */
  def pulumiEnv: Map[String, String] = Map.empty

  /**
   * Local Pulumi packages by name -> source, for packages that are not in the registry. Relative path sources are
   * resolved against the workspace root; anything containing a URL scheme is passed through untouched.
   */
  def pulumiLocalPackages: Map[String, String] = Map.empty

  /** The Pulumi project directory operations run in; defaults to this module's directory. */
  def pulumiProjectDir: os.Path = moduleDir

  override def mvnDeps = Task(super.mvnDeps() ++ Seq(mvn"com.pulumi:pulumi:$pulumiSdkVersion"))

  /**
   * Concatenates `META-INF/services` files instead of keeping the first. Without this the assembly keeps only
   * grpc-netty-shaded's `io.grpc.NameResolverProvider` and drops grpc-core's DNS resolver, so the program cannot
   * resolve the engine's `127.0.0.1:<port>` address and every run dies with a gRPC connect error.
   */
  override def assemblyRules =
    super.assemblyRules ++ Seq(Assembly.Rule.AppendPattern("META-INF/services/.*", "\n"))

  /**
   * Pulumi launches the program with `java -jar`, so the executable prelude buys nothing — and it breaks assemblies
   * over 65535 zip entries, which any build pulling in a provider SDK reaches.
   */
  override def prependShellScript = Task("")

  private def resolveSource(source: String): String =
    if (source.contains("://")) source else os.Path(source, BuildCtx.workspaceRoot).toString

  /**
   * Generated Java SDK roots (one src/main per local package), produced by pulumi package gen-sdk. Requires the pulumi
   * CLI to be able to boot each package's plugin from its source.
   *
   * gen-sdk emits sources plus a gradle build, but the version.txt and plugin.json resources the SDK loads at
   * class-init are only produced by that gradle build, so they are written here instead.
   */
  def pulumiPackageSdks: T[Seq[PathRef]] = Task {
    pulumiLocalPackages.toSeq.sortBy(_._1).map { case (name, source) =>
      val out       = Task.dest / name
      os.call(
        cmd = (pulumiCli, "package", "gen-sdk", resolveSource(source), "--language", "java", "--out", out.toString),
        env = Task.env ++ pulumiEnv,
        cwd = Task.dest,
        stdout = os.Inherit,
        stderr = os.Inherit
      )
      val sdk       = out / "java"
      val info      = PulumiModule
        .sdkPackageInfo(os.read(sdk / "settings.gradle"), os.read(sdk / "build.gradle"))
        .getOrElse(throw new IllegalStateException(s"Could not read package name and version of generated SDK $name"))
      val resources = sdk / "src" / "main" / "resources" / os.RelPath(info.resourceDir)
      os.makeDir.all(resources)
      os.write.over(resources / "version.txt", info.version)
      os.write.over(resources / "plugin.json", PulumiModule.pluginJson(info))
      PathRef(sdk / "src" / "main")
    }
  }

  /** Generated SDKs annotate every optional value with `@Nullable`, which the Pulumi SDK does not bring in. */
  override def compileMvnDeps = Task {
    super.compileMvnDeps() ++ Option.when(pulumiLocalPackages.nonEmpty)(mvn"com.google.code.findbugs:jsr305:3.0.2")
  }

  /**
   * The Pulumi SDK declares protobuf, gRPC and guava as runtime dependencies, but Zinc reflects over SDK classes whose
   * signatures mention them while analysing generated Java sources, and fails the compile if they are missing.
   * Compiling against the runtime set keeps that analysis whole.
   */
  override def compileClasspath = Task(super.compileClasspath() ++ resolvedRunMvnDeps())

  override def generatedSources = Task(super.generatedSources() ++ pulumiPackageSdks().map(p => PathRef(p.path / "java")))

  override def resources = Task {
    super.resources() ++ pulumiPackageSdks()
      .map(p => PathRef(p.path / "resources"))
      .filter(p => os.exists(p.path))
  }

  private def withStack(stack: String)(f: WorkspaceStack => Unit) = Task.Anon {
    val packages = pulumiLocalPackages.map { case (n, s) => n -> resolveSource(s) }
    BuildCtx.withFilesystemCheckerDisabled[Unit] {
      os.write.over(
        pulumiProjectDir / "Pulumi.yaml",
        PulumiModule.projectYaml(pulumiProjectName, assembly().path, packages)
      )
      val opts = LocalWorkspaceOptions.builder().environmentVariables((Task.env ++ pulumiEnv).asJava).build()
      Using.resource(LocalWorkspace.createOrSelectStack(stack, pulumiProjectDir.toNIO, opts))(f)
    }
  }

  private def out(line: String): Unit = println(line)
  private def err(line: String): Unit = System.err.println(line)

  /** Previews the changes an up would apply to the stack. */
  def preview(stack: String = pulumiStack) = Task.Command(exclusive = true) {
    withStack(stack) { s =>
      s.preview(PreviewOptions.builder().onStandardOutput(out).onStandardError(err).build())
      ()
    }()
  }

  /** Creates or updates the stack's resources. */
  def up(stack: String = pulumiStack) = Task.Command(exclusive = true) {
    withStack(stack) { s =>
      s.up(UpOptions.builder().onStandardOutput(out).onStandardError(err).build())
      ()
    }()
  }

  /** Refreshes the stack's state from the actual infrastructure. */
  def refresh(stack: String = pulumiStack) = Task.Command(exclusive = true) {
    withStack(stack) { s =>
      s.refresh(RefreshOptions.builder().onStandardOutput(out).onStandardError(err).build())
      ()
    }()
  }

  /** Destroys all resources in the stack. */
  def destroy(stack: String = pulumiStack) = Task.Command(exclusive = true) {
    withStack(stack) { s =>
      s.destroy(DestroyOptions.builder().onStandardOutput(out).onStandardError(err).build())
      ()
    }()
  }
}

object PulumiModule {

  /** Identity of a generated Pulumi Java SDK: where its classpath resources go, its plugin name and version. */
  case class SdkPackageInfo(resourceDir: String, name: String, version: String)

  private val rootProjectName = """rootProject\.name\s*=\s*"([^"]+)"""".r.unanchored
  private val fallbackVersion = """"unspecified"\s*\?\s*"([^"]+)"""".r.unanchored

  /**
   * Reads the package identity out of the gradle files emitted by `pulumi package gen-sdk`, so the plugin can write the
   * `version.txt` and `plugin.json` classpath resources that generated SDKs load at class-init time and that only the
   * gradle build would otherwise create.
   */
  def sdkPackageInfo(settingsGradle: String, buildGradle: String): Option[SdkPackageInfo] =
    (settingsGradle, buildGradle) match {
      case (rootProjectName(project), fallbackVersion(version)) =>
        Some(SdkPackageInfo(project.replace('.', '/'), project.split('.').last, version))
      case _                                                    => None
    }

  /** Renders the plugin.json a generated SDK expects next to its version.txt. */
  def pluginJson(info: SdkPackageInfo): String =
    s"""{
       |    "resource": true,
       |    "name": "${info.name}",
       |    "version": "${info.version}"
       |}""".stripMargin

  /** Renders a Pulumi.yaml for a java runtime project whose program is a prebuilt jar. */
  def projectYaml(name: String, binary: os.Path, packages: Map[String, String]): String = {
    val base =
      s"""name: $name
         |runtime:
         |  name: java
         |  options:
         |    binary: $binary
         |""".stripMargin
    if (packages.isEmpty) base
    else base + "packages:\n" + packages.toSeq.sorted.map { case (n, s) => s"  $n: $s\n" }.mkString
  }
}

package atn.mill

import mill.*
import mill.api.{Discover, ModuleRef, PathRef}
import mill.javalib.api.CompilationResult
import mill.scalalib.*
import mill.testkit.{TestRootModule, UnitTester}
import utest.*

import java.io.{ByteArrayOutputStream, PrintStream}

/**
 * Drives the site-building tasks of [[DocsModule]] through `UnitTester`. The documented module stubs its compilation
 * and classpaths, and the scaladoc the plugin forks is [[FakeScaladoc]], so a run takes well under a second and every
 * argument the plugin assembles can be asserted on.
 */
object DocsSiteTest extends TestSuite:

  /** The value `task` evaluates to under `eval`; an assertion error otherwise. */
  private def value[T](eval: UnitTester, task: Task[T]): T =
    eval(task).fold(failure => throw new java.lang.AssertionError(s"task failed: $failure"), _.value)

  /** A workspace with a README referencing a missing image and, optionally, a hand-authored docs/ page. */
  private def workspace(withDocsDir: Boolean): os.Path =
    val root = os.temp.dir()
    os.write(root / "README.md", "# Site\n\nWelcome.\n\n![diagram](images/missing.png)\n")
    os.write(root / "lib" / "dep.jar", "", createFolders = true)
    if withDocsDir then os.write(root / "docs" / "_docs" / "hand.md", "# Hand written\n", createFolders = true)
    root

  /** `body` under a tester over `ws`, with what it wrote to stdout and stderr: (result, out, err). */
  private def captured[T](build: SiteBuild, ws: os.Path)(body: UnitTester => T): (T, String, String) =
    val out    = new ByteArrayOutputStream
    val err    = new ByteArrayOutputStream
    val result = UnitTester(build, ws, outStream = new PrintStream(out), errStream = new PrintStream(err)).scoped(body)
    (result, out.toString, err.toString)

  /** The scaladoc arguments every run starts with, up to where the extra options go. */
  private def fixedScaladocArgs(dest: os.Path, version: String, siteRoot: os.Path, classpath: String): Seq[String] =
    Seq(
      "-d",
      dest.toString,
      "-project",
      "site-project",
      "-project-version",
      version,
      "-siteroot",
      siteRoot.toString,
      "-classpath",
      classpath,
      "-no-link-warnings"
    )

  /** Builds the site with `task`: (site dir, scaladoc arguments after the fixed ones, class directory of `lib`). */
  private def built(build: SiteBuild, task: SiteBuild => T[PathRef]): (os.Path, Seq[String], String) =
    UnitTester(build, workspace(withDocsDir = true)).scoped { eval =>
      val site    = value(eval, task(build)).path
      val staged  = value(eval, build.docs.preparedSiteRoot).path
      val classes = value(eval, build.lib.compile).classes.path
      val fixed   = fixedScaladocArgs(site, "2.0.0", staged, (build.moduleDir / "lib" / "dep.jar").toString)
      val args    = FakeScaladoc.argsIn(site)
      assert(args.take(fixed.size) == fixed)
      assert(FakeScaladoc.cwdIn(site) == build.moduleDir)
      (site, args.drop(fixed.size), classes.toString)
    }

  val tests = Tests:

    test("defaults - no excludes, source links or static sources; docs/ under the root is the site root") {
      val build = new SiteBuild
      assert(build.bare.excludedModules == Set.empty[String])
      assert(build.bare.sourceLinks == Seq.empty[String])
      assert(build.bare.staticDocSources == Seq.empty[os.RelPath])
      assert(build.bare.defaultTask() == "local")
      UnitTester(build, workspace(withDocsDir = true)).scoped { eval =>
        assert(value(eval, build.bare.docsSiteRoot).path == build.moduleDir / "docs")
        assert(value(eval, build.bare.staticDocSourcePaths).isEmpty)
        assert(value(eval, build.docs.staticDocSourcePaths).map(_.path) == Seq(build.moduleDir / "README.md"))
      }
    }

    test("preparedSiteRoot - copies docs/ and transforms the static sources into _docs, logging their warnings") {
      val build            = new SiteBuild
      val (staged, _, err) =
        captured(build, workspace(withDocsDir = true))(eval => value(eval, build.docs.preparedSiteRoot).path)
      assert(os.read(staged / "_docs" / "hand.md") == "# Hand written\n")
      assert(os.read(staged / "_docs" / "index.md").startsWith("---\ntitle: site-project\n---\n"))
      assert(err.contains(s"[DocTransformer] image not found: ${build.moduleDir / "images" / "missing.png"}"))
    }

    test("preparedSiteRoot - without a docs/ directory _docs holds the transformed sources alone") {
      val build = new SiteBuild
      UnitTester(build, workspace(withDocsDir = false)).scoped { eval =>
        val staged = value(eval, build.docs.preparedSiteRoot).path
        assert(os.list(staged).map(_.last) == Seq("_docs"))
        assert(os.list(staged / "_docs").map(_.last) == Seq("index.md"))
      }
    }

    test("listModules - prints the documented modules") {
      val build       = new SiteBuild
      val (_, out, _) = captured(build, workspace(withDocsDir = true))(eval => value(eval, build.docs.listModules()))
      assert(out.linesIterator.contains("lib"))
      assert(!out.contains("docs"))
    }

    test("local - forks scaladoc over the documented classes with the site root and no deployment options") {
      val build                  = new SiteBuild
      val (site, extra, classes) = built(build, _.docs.local)
      assert(site == build.moduleDir / "out" / "docs" / "local.dest" / "site")
      assert(extra == Seq(classes))
    }

    test("runScaladoc - called directly, the extra options default to none and the fork runs in the root directory") {
      val build = new SiteBuild
      UnitTester(build, workspace(withDocsDir = true)).scoped { _ =>
        val dest     = build.moduleDir / "direct-site"
        val classes  = build.moduleDir / "classes"
        os.makeDir.all(dest)
        build.docs.runScaladoc(
          dest,
          Seq(classes),
          Seq.empty,
          Seq(FakeScaladoc.classes),
          "3.0.0",
          build.moduleDir / "root"
        )
        val expected = fixedScaladocArgs(dest, "3.0.0", build.moduleDir / "root", "") :+ classes.toString
        assert(FakeScaladoc.argsIn(dest) == expected)
        assert(FakeScaladoc.cwdIn(dest) == build.moduleDir)
      }
    }

    test("site - adds the sourceLinks after the fixed options and before the class directories") {
      val build                  = new SiteBuild
      val (site, extra, classes) = built(build, _.docs.site)
      assert(site == build.moduleDir / "out" / "docs" / "site.dest" / "site")
      assert(extra == Seq("-source-links:github://acme/site", classes))
    }

/** A `dotty.tools.scaladoc.Main` the docs tasks fork by class name: it records its arguments and working directory. */
object FakeScaladoc:
  private val argsFile = "scaladoc-args.txt"
  private val cwdFile  = "scaladoc-cwd.txt"

  /** Class directory of the stand-in, compiled once per test run with the JDK's own compiler. */
  lazy val classes: os.Path =
    val dir    = os.temp.dir()
    val source = dir / "Main.java"
    os.write(
      source,
      s"""package dotty.tools.scaladoc;
         |public class Main {
         |  public static void main(String[] args) throws Exception {
         |    java.nio.file.Path dest = null;
         |    for (int i = 0; i + 1 < args.length; i++) if (args[i].equals("-d")) dest = java.nio.file.Paths.get(args[i + 1]);
         |    java.nio.file.Files.write(dest.resolve("$argsFile"), java.util.Arrays.asList(args));
         |    java.nio.file.Files.writeString(dest.resolve("$cwdFile"), System.getProperty("user.dir"));
         |  }
         |}
         |""".stripMargin
    )
    val status =
      javax.tools.ToolProvider.getSystemJavaCompiler.run(null, null, null, "-d", dir.toString, source.toString)
    require(status == 0, "the scaladoc stand-in did not compile")
    dir

  def argsIn(site: os.Path): Seq[String] = os.read.lines(site / argsFile)
  def cwdIn(site: os.Path): os.Path      = os.Path(os.read(site / cwdFile))

// Nested in a class for the same reason as BasicDocsRoot: Mill's reflection does not see objects nested in an object.
abstract class SiteDocsRoot extends TestRootModule:

  /** The documented module: compilation and classpaths are stubbed so neither a compiler nor a resolver runs. */
  object lib extends ScalaModule:
    def scalaVersion = "3.8.4"

    override def compile           = Task {
      val classes = Task.dest / "classes"
      os.makeDir.all(classes)
      CompilationResult(Task.dest / "zinc", PathRef(classes))
    }
    override def compileClasspath  = Task.Sources(moduleDir / "dep.jar")
    override def scalaDocClasspath = Task.Sources(FakeScaladoc.classes)

  object docs extends DocsModule:
    def docProjectName            = "site-project"
    def docVersion                = Task("2.0.0")
    def docRootModule             = ModuleRef(SiteDocsRoot.this)
    override def sourceLinks      = Seq("-source-links:github://acme/site")
    override def staticDocSources = Seq(os.rel / "README.md")

  /** Every knob at its default. */
  object bare extends DocsModule:
    def docProjectName = "site-project"
    def docVersion     = Task("2.0.0")
    def docRootModule  = ModuleRef(SiteDocsRoot.this)

class SiteBuild extends SiteDocsRoot:
  lazy val millDiscover: Discover = Discover[this.type]

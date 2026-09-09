package atn.mill

import mill.*
import mill.api.{Discover, Result}
import mill.api.daemon.Logger.DummyLogger
import mill.scalalib.*
import mill.testkit.{TestRootModule, UnitTester}
import org.scalacheck.{Gen, Prop, Test => PropTest}
import scalafix.interfaces.{ScalafixError, ScalafixException}
import scalafix.interfaces.ScalafixError.*
import utest.*

import java.util.zip.ZipFile
import scala.jdk.CollectionConverters.*

/**
 * Drives [[ScalafixSupport]] without scalafix-cli: the tool-classloader arguments are a [[FakeScalafixArguments]], so
 * the per-module argument assembly, the run outcome and the error descriptions are observed directly; the Mill tasks
 * and commands run under `UnitTester` on modules without Scala sources, the one case that never touches Scalafix.
 */
object ScalafixSupportTest extends TestSuite:

  private val workspace = os.Path("/ws")

  /** Inputs of a lint of two sources with the workspace config and `--check`. */
  private val inputs = ScalafixSupport.ModuleInputs(
    scalaVersion = "3.8.4",
    scalacOptions = Seq("-Wunused:all"),
    sources = Seq(workspace / "app" / "src" / "A.scala", workspace / "app" / "src" / "B.scala"),
    classpath = Seq(workspace / "out" / "app" / "compile.dest" / "classes", workspace / "lib" / "scala3-library.jar"),
    config = Some(workspace / ".scalafix.conf"),
    args = Seq("--check"),
    workingDirectory = workspace
  )

  private val emptyKey: ScalafixSupport.ToolClasspathKey = ("3.8.4", Seq.empty, Seq.empty, Seq.empty)

  /** What [[ScalafixSupport.describeError]] says for each error, with `validation` as the command-line check. */
  private def descriptions(validation: Option[ScalafixException]): Map[ScalafixError, String] = Map(
    ParseError             -> "A source file failed to be parsed",
    CommandLineError       -> validation.fold("A command-line argument was parsed incorrectly")(_.getMessage),
    MissingSemanticdbError ->
      "A semantic rewrite was run on a source file that has no associated META-INF/semanticdb/.../*.semanticdb",
    StaleSemanticdbError   ->
      """The source file contents on disk have changed since the last compilation with the SemanticDB compiler
        |plugin. To resolve this error re-compile the project and re-run Scalafix""".stripMargin,
    TestError              ->
      "A Scalafix test error was reported. Run `scalafix` without `--check` or `--diff` to fix the error",
    LinterError            -> "A Scalafix linter error was reported",
    NoFilesError           -> "No files were provided to Scalafix so nothing happened",
    NoRulesError           -> "No Scalafix rules were found. Make sure a `rules` set is defined in .scalafix.conf",
    UnexpectedError        -> "Something unexpected happened running Scalafix"
  )

  private def assembled(base: FakeScalafixArguments, inputs: ScalafixSupport.ModuleInputs): FakeScalafixArguments =
    ScalafixSupport.moduleArguments(base, inputs) match
      case fake: FakeScalafixArguments => fake
      case other                       => throw new java.lang.AssertionError(s"Not the fake arguments: $other")

  /** Evaluates `task` on a fresh fixture in a fresh workspace, then `check`s the fixture and the tester's value. */
  private def withBuild[T](check: (ScalafixBuild, UnitTester) => T): T =
    val build = new ScalafixBuild()
    UnitTester(build, os.temp.dir()).scoped(eval => check(build, eval))

  /** The value `eval` computes for `task`, or an assertion error carrying the failure. */
  private def value[T](eval: UnitTester, task: Task[T]): T =
    eval(task).fold(failure => throw new java.lang.AssertionError(s"Expected success but got $failure"), _.value)

  private def checkProp(prop: Prop): Unit =
    assert(PropTest.check(prop)(identity).passed)

  val tests = Tests:

    test("scalafixConfigIn - the root's .scalafix.conf when it exists, nothing otherwise") {
      val root = os.temp.dir()
      assert(ScalafixSupport.scalafixConfigIn(root) == None)
      os.write(root / ".scalafix.conf", "rules = [NoTodo]")
      assert(ScalafixSupport.scalafixConfigIn(root) == Some(root / ".scalafix.conf"))
    }

    test("moduleArguments - every per-module input lands on the tool-classloader arguments") {
      val fake = assembled(FakeScalafixArguments(rules = 3), inputs)
      assert(fake.rules == 3)
      assert(fake.parsedArguments == Seq("--check"))
      assert(fake.workingDirectory == Some(workspace.toNIO))
      assert(fake.config == Some((workspace / ".scalafix.conf").toNIO))
      assert(fake.classpath == inputs.classpath.map(_.toNIO))
      assert(fake.scalaVersion == Some("3.8.4"))
      assert(fake.scalacOptions == Seq("-Wunused:all"))
      assert(fake.paths == inputs.sources.map(_.toNIO))
    }

    test("moduleArguments - no workspace config is passed as an absent config") {
      assert(assembled(FakeScalafixArguments(), inputs.copy(config = None)).config == None)
    }

    test("run - a module without sources succeeds without forcing the tool classloader") {
      val result = ScalafixSupport.run(
        DummyLogger,
        throw new java.lang.AssertionError("the tool classloader was forced"),
        inputs.copy(sources = Seq.empty)
      )
      assert(result == Result.Success(()))
    }

    test("run - succeeds when Scalafix reports no error") {
      assert(ScalafixSupport.run(DummyLogger, FakeScalafixArguments(rules = 2), inputs) == Result.Success(()))
    }

    test("run - every reported error is one line of the failure, in report order") {
      val result =
        ScalafixSupport.run(DummyLogger, FakeScalafixArguments(errors = Seq(LinterError, NoRulesError)), inputs)
      assert(
        result.toEither == Left(
          "A Scalafix linter error was reported\n" +
            "No Scalafix rules were found. Make sure a `rules` set is defined in .scalafix.conf"
        )
      )
    }

    test("run - the failure lists the description of each error, however many and whichever they are") {
      val errors = Gen.listOf(Gen.oneOf(ScalafixError.values().toSeq))
      checkProp(Prop.forAll(errors) { reported =>
        val fake     = FakeScalafixArguments(errors = reported)
        val expected =
          if reported.isEmpty then Right(())
          else Left(reported.map(descriptions(None)).mkString("\n"))
        ScalafixSupport.run(DummyLogger, fake, inputs).toEither == expected
      })
    }

    test("describeError - each ScalafixError has its own description, and every value has one") {
      val fake = FakeScalafixArguments()
      val all  = descriptions(None)
      assert(all.keySet == ScalafixError.values().toSet)
      all.foreach((error, description) => assert(ScalafixSupport.describeError(error, fake) == description))
    }

    test("describeError - a command-line error carries Scalafix's own validation message when there is one") {
      val invalid = new ScalafixException("Unknown flag --no-such-flag")
      val fake    = FakeScalafixArguments(validation = Some(invalid))
      assert(ScalafixSupport.describeError(CommandLineError, fake) == "Unknown flag --no-such-flag")
      assert(ScalafixSupport.describeError(ParseError, fake) == "A source file failed to be parsed")
    }

    test("cachedArguments - the fetch runs once per key; an equal key gets the same instance without fetching") {
      val key    = emptyKey.copy(_1 = "3.8.4-cached")
      val first  = ScalafixSupport.cachedArguments(key)(FakeScalafixArguments(rules = 1))
      val second = ScalafixSupport.cachedArguments(key.copy(_4 = Seq.empty)) {
        throw new java.lang.AssertionError("fetched twice for one key")
      }
      assert(first eq second)
      assert(first == FakeScalafixArguments(rules = 1))
    }

    test("cachedArguments - the Scala version, repositories, rule deps and tool classpath each make a new key") {
      val base   = emptyKey.copy(_1 = "3.8.4-keyed")
      val keys   = Seq(
        base,
        base.copy(_1 = "3.8.3-keyed"),
        base.copy(_2 = Seq(coursier.MavenRepository("https://repo.example.invalid/maven2"))),
        base.copy(_3 = Seq(mvn"org.typelevel::typelevel-scalafix:0.5.0")),
        base.copy(_4 = Seq(workspace / "out" / "rule" / "jar.dest" / "out.jar"))
      )
      val cached =
        keys.zipWithIndex.map((key, i) => ScalafixSupport.cachedArguments(key)(FakeScalafixArguments(rules = i)))
      assert(cached == keys.indices.map(i => FakeScalafixArguments(rules = i)))
    }

    test("scalafixMvnDeps, scalafixToolModules and scalafixToolClasspath - default to empty") {
      withBuild { (build, eval) =>
        assert(build.plain.scalafixToolModules == Seq.empty)
        assert(value(eval, build.plain.scalafixMvnDeps) == Seq.empty)
        assert(value(eval, build.plain.scalafixToolClasspath) == Seq.empty)
      }
    }

    test("scalafixToolClasspath - each tool module contributes its jar then its run classpath, in module order") {
      withBuild { (build, eval) =>
        os.write(build.moduleDir / "rule" / "src" / "NoTodo.scala", "object NoTodo", createFolders = true)
        val classpath = value(eval, build.tooled.scalafixToolClasspath)
        val rule      = value(eval, build.rule.jar) +: value(eval, build.rule.runClasspath)
        val rule2     = value(eval, build.rule2.jar) +: value(eval, build.rule2.runClasspath)
        assert(classpath == rule ++ rule2)
        assert(rule.size > 1)
        val entries   = new ZipFile(classpath.head.path.toIO).entries().asScala.map(_.getName).toSet
        assert(entries.contains("NoTodo.class"))
        assert(classpath.exists(_.path.last == "scala-library-3.8.4.jar"))
      }
    }

    test("scalafix and scalafixCheck - a module without Scala sources succeeds without resolving its rules") {
      // `app` declares a rule dependency that does not exist: had either command loaded Scalafix, resolving it fails.
      withBuild { (build, eval) =>
        assert(value(eval, build.app.scalafix()) == ())
        assert(value(eval, build.app.scalafixCheck()) == ())
      }
    }

// --- Fixtures: the shape of the example workspace's build.mill ---

// The modules are nested in a class rather than in an object: Scala 3 compiles objects nested in an object to static
// fields that Mill's reflective child discovery does not see, whereas a real build.mill is wrapped in a class by
// Mill's codegen and reflects fine. Each test instantiates the fixture, so every UnitTester gets a fresh module
// directory.
abstract class ScalafixRoot extends TestRootModule:
  object plain extends ScalaModule with ScalafixSupport:
    def scalaVersion = "3.8.4"

  object app extends ScalaModule with ScalafixSupport:
    def scalaVersion             = "3.8.4"
    override def scalafixMvnDeps = Task(Seq(mvn"org.example::no-such-rules:0.0.0"))

  object rule extends ScalaModule:
    def scalaVersion = "3.8.4"

  object rule2 extends ScalaModule:
    def scalaVersion = "3.8.4"

  object tooled extends ScalaModule with ScalafixSupport:
    def scalaVersion                 = "3.8.4"
    override def scalafixToolModules = Seq(rule, rule2)

class ScalafixBuild extends ScalafixRoot:
  lazy val millDiscover: Discover = Discover[this.type]

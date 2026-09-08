package atn.mill

import utest._
import mill._
import mill.api.{Discover, ModuleRef, Result, SelectMode}
import mill.testkit.{TestRootModule, UnitTester}
import mill.scalalib._

object DocsModuleTest extends TestSuite:

  val tests = Tests:

    test("DocTransformer.titleFromFilename") {
      assert(DocTransformer.titleFromFilename("README.md", "test-project") == "test-project")
      assert(DocTransformer.titleFromFilename("TOPICS.md", "test-project") == "Topics")
    }

    test("DocsModule.excludedModules - defaults to empty") {
      assert(BasicDocsBuild.docs.excludedModules == Set("excluded"))
    }

    test("DocsModule.docProjectName") {
      assert(BasicDocsBuild.docs.docProjectName == "test-project")
    }

    test("DocsModule.docRootModule - is not reflected as a child module") {
      // Mill's resolver enumerates every public no-arg Module-returning method as a child
      // module, so a Module-typed docRootModule would surface as `docs.docRootModule`.
      assert(BasicDocsBuild.docs.moduleDirectChildren.isEmpty)
    }

    test("DocsModule.allModules - discovers ScalaModules under docRootModule") {
      assert(BasicDocsBuild.docs.allModules.map(_.moduleSegments.render).toSet == Set("basic"))
    }

    test("DocsModule - transitive wildcard selectors resolve through docRootModule") {
      // Regression: `./mill resolve __` and `__.compile` failed with "Cyclic module reference
      // detected at docs.docRootModule" because the resolver treated the root reference as a
      // child module whose class had already been visited on the way down.
      UnitTester(BasicDocsBuild, os.temp.dir()).scoped { eval =>
        eval.evaluator.resolveSegments(Seq("__"), SelectMode.Multi) match {
          case Result.Success(segments) =>
            val rendered = segments.map(_.render)
            assert(rendered.contains("docs.listModules"))
            assert(rendered.contains("docs.local"))
            assert(rendered.contains("docs.site"))
            assert(rendered.contains("basic.compile"))
            assert(!rendered.exists(_.startsWith("docs.docRootModule")))
          case f: Result.Failure        =>
            throw new java.lang.AssertionError(s"resolve __ failed: ${f.error}")
        }
        eval.evaluator.resolveTasks(Seq("__.compile"), SelectMode.Multi) match {
          case Result.Success(tasks) =>
            assert(tasks.map(_.toString).toSet == Set("basic.compile", "excluded.compile"))
          case f: Result.Failure     =>
            throw new java.lang.AssertionError(s"resolve __.compile failed: ${f.error}")
        }
      }
    }

// --- Test Fixtures ---

// The modules are nested in a class rather than directly in the root object: Scala 3 compiles
// objects nested in an object to static fields that Mill's reflective child discovery does not
// see, whereas a real build.mill is wrapped in a class by Mill's codegen and reflects fine.
abstract class BasicDocsRoot extends TestRootModule:
  object excluded extends ScalaModule:
    def scalaVersion = "3.8.2"

  object basic extends ScalaModule:
    def scalaVersion = "3.8.2"

  object docs extends DocsModule:
    def docProjectName           = "test-project"
    def docVersion               = Task("0.1.0")
    def docRootModule            = ModuleRef(BasicDocsBuild)
    override def excludedModules = Set("excluded")

object BasicDocsBuild extends BasicDocsRoot:
  lazy val millDiscover: Discover = Discover[this.type]

package atn.mill

import utest._
import mill._
import mill.api.Discover
import mill.testkit.TestRootModule
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

    test("DocsModule.moduleDirectChildren - excludes docRootModule") {
      val children = BasicDocsBuild.docs.moduleDirectChildren
      assert(!children.exists(_ eq BasicDocsBuild))
    }

// --- Test Fixtures ---

object BasicDocsBuild extends TestRootModule:
  object excluded extends ScalaModule:
    def scalaVersion = "3.8.2"

  object basic extends ScalaModule:
    def scalaVersion = "3.8.2"

  object docs extends DocsModule:
    def docProjectName          = "test-project"
    def docVersion              = Task("0.1.0")
    def docRootModule           = BasicDocsBuild
    override def excludedModules = Set("excluded")

  lazy val millDiscover: Discover = Discover[this.type]

package atn.mill

import utest._
import mill._
import mill.api.Discover
import mill.testkit.TestRootModule
import mill.scalalib._

object DocsModuleTest extends TestSuite:

  val tests = Tests:

    test("allModules - discovers ScalaModules from root") {
      val docs       = BasicDocsBuild.docs
      val discovered = docs.allModules.map(_.moduleSegments.render).toSet
      // discovers project ScalaModules
      assert(discovered.contains("basic"))
      // excludes configured modules
      assert(!discovered.contains("excluded"))
    }

// --- Test Fixtures ---

object BasicDocsBuild extends TestRootModule:
  object excluded extends ScalaModule:
    def scalaVersion = "3.8.2"

  object basic extends ScalaModule:
    def scalaVersion = "3.8.2"

  object docs extends DocsModule:
    def docProjectName  = "test-project"
    def docVersion      = Task("0.1.0")
    def docRootModule   = BasicDocsBuild
    override def excludedModules = Set("excluded")

  lazy val millDiscover: Discover = Discover[this.type]

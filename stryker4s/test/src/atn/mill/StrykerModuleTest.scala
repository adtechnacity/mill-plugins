package atn.mill

import utest._
import mill._
import mill.scalalib._
import mill.api.{Discover, Result}
import mill.testkit.{TestRootModule, UnitTester}

object StrykerModuleTest extends TestSuite:

  val tests = Tests:

    test("StrykerThresholds - default values") {
      val t = StrykerThresholds()
      assert(t.high == 80)
      assert(t.low == 60)
      assert(t.break == 0)
    }

    test("StrykerThresholds - custom values") {
      val t = StrykerThresholds(high = 90, low = 70, break = 50)
      assert(t.high == 90)
      assert(t.low == 70)
      assert(t.break == 50)
    }

    test("buildConf - generates valid config map") {
      val conf = StrykerModule.buildConf(
        excludedMutations = Seq("StringLiteral"),
        thresholds = StrykerThresholds(high = 90, low = 70, break = 50),
        reporters = Seq("console", "html"),
        concurrency = 2,
        scalaDialect = "scala3"
      )
      assert(!conf.contains("mutate"))
      assert(conf("excluded-mutations") == ujson.Arr("StringLiteral"))
      assert(conf("thresholds")("high").num == 90)
      assert(conf("thresholds")("low").num == 70)
      assert(conf("thresholds")("break").num == 50)
      assert(conf("reporters") == ujson.Arr("console", "html"))
      assert(conf("concurrency").num == 2)
      assert(conf("scala-dialect").str == "scala3")
    }

    test("writeConf - writes valid JSON config") {
      val tmpDir   = os.temp.dir()
      val confFile = tmpDir / "stryker4s.conf"
      val conf     = StrykerModule.buildConf(
        excludedMutations = Seq.empty,
        thresholds = StrykerThresholds(),
        reporters = Seq("console", "html"),
        concurrency = 2,
        scalaDialect = "scala3"
      )
      StrykerModule.writeConf(conf, tmpDir, confFile)

      val content = ujson.read(os.read(confFile))
      assert(content("stryker4s")("base-dir").str == tmpDir.toString)
    }

    test("mutatePatterns - one include glob per source root, no excludes by default") {
      val ps = StrykerModule.mutatePatterns(Seq("libs/data_core/src"), Seq.empty)
      assert(ps == Seq("libs/data_core/src/**/*.scala"))
    }

    test("mutatePatterns - excluded files become !-prefixed negative globs after the includes") {
      val ps = StrykerModule.mutatePatterns(
        Seq("libs/data_core/src", "libs/data_core/gen"),
        Seq("libs/data_core/src/atn/data_core/KeyValueStore.scala", "**/Generated.scala")
      )
      assert(
        ps == Seq(
          "libs/data_core/src/**/*.scala",
          "libs/data_core/gen/**/*.scala",
          "!libs/data_core/src/atn/data_core/KeyValueStore.scala",
          "!**/Generated.scala"
        )
      )
      // stryker4s partitions `mutate` on the `!` prefix, so every exclude must carry exactly one.
      assert(ps.count(_.startsWith("!")) == 2)
      assert(!ps.exists(_.startsWith("!!")))
    }

    test("compilerArtifactName - Scala 3 uses the _3-suffixed artifact") {
      assert(StrykerModule.compilerArtifactName("3.8.4") == "scala3-compiler_3")
      assert(StrykerModule.compilerArtifactName("3.3.7") == "scala3-compiler_3")
    }

    test("compilerArtifactName - Scala 2 uses the unsuffixed artifact") {
      // `scala3-compiler_3:2.13.16` does not exist; asking for it aborts the run before instrumenting anything.
      assert(StrykerModule.compilerArtifactName("2.13.16") == "scala-compiler")
      assert(StrykerModule.compilerArtifactName("2.12.20") == "scala-compiler")
    }

    test("compilerMainClass - matches the compiler artifact for each Scala major") {
      // Resolving scala-compiler but invoking dotty.tools.dotc.Main fails with
      // "Could not find or load main class", so these two must agree.
      assert(StrykerModule.compilerMainClass("3.8.4") == "dotty.tools.dotc.Main")
      assert(StrykerModule.compilerMainClass("2.13.16") == "scala.tools.nsc.Main")
      assert(StrykerModule.compilerArtifactName("2.13.16") == "scala-compiler")
    }

    test("filterScalacOptions - removes fatal warnings and unused") {
      val opts     = Seq("-Xfatal-warnings", "-deprecation", "-Wunused:all", "-Yexplicit-nulls")
      val filtered = StrykerModule.filterScalacOptions(opts)
      assert(filtered == Seq("-deprecation", "-Yexplicit-nulls"))
    }

    test("Stryker4sModule - strykerConf task generates config") {
      UnitTester(TestStrykerBuild, os.temp.dir()).scoped { eval =>
        eval("strykerConf") match {
          case Right(r) =>
            val conf = r.value.asInstanceOf[Vector[?]].head.asInstanceOf[Map[String, ujson.Value]]
            assert(conf("scala-dialect").str == "scala3future")
            assert(conf("concurrency").num > 0)
            assert(conf("reporters").arr.map(_.str).contains("console"))
          case Left(e)  =>
            throw new java.lang.AssertionError(s"Expected success but got: $e")
        }
      }
    }

object TestStrykerBuild extends TestRootModule with Stryker4sModule:
  def scalaVersion      = "3.8.2"
  def strykerVersion    = "0.19.1"
  def strykerTestModule = test
  object test extends ScalaTests with TestModule.Utest:
    override def mvnDeps = Seq.empty
  lazy val millDiscover: Discover = Discover[this.type]

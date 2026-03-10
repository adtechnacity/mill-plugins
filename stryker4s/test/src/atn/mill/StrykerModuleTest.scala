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

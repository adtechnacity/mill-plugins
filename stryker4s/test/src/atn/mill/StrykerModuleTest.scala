package atn.mill

import utest._
import mill._
import mill.scalalib._
import mill.api.{Discover, Result}
import mill.testkit.{TestRootModule, UnitTester}

object StrykerModuleTest extends TestSuite:

  val tests = Tests:

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

    test("writeConf - writes valid JSON config with the default thresholds") {
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
      assert(content("stryker4s")("thresholds") == ujson.Obj("high" -> 80, "low" -> 60, "break" -> 0))
    }

    test("mutatePatterns - one include glob per source root, or the included files, then !-prefixed excludes") {
      // (source roots, included files, excluded files, expected mutate patterns)
      val cases = Seq(
        (Seq("libs/data_core/src"), Seq.empty, Seq.empty, Seq("libs/data_core/src/**/*.scala")),
        (
          Seq("libs/data_core/src", "libs/data_core/gen"),
          Seq.empty,
          Seq("libs/data_core/src/atn/data_core/KeyValueStore.scala", "**/Generated.scala"),
          Seq(
            "libs/data_core/src/**/*.scala",
            "libs/data_core/gen/**/*.scala",
            "!libs/data_core/src/atn/data_core/KeyValueStore.scala",
            "!**/Generated.scala"
          )
        ),
        // A PR that touched two files should mutate exactly those, not every file of every changed module.
        (
          Seq("devx/src", "devx/gen"),
          Seq("devx/src/atn/mill/CodeScene.scala", "core/src/**/*.scala"),
          Seq("**/Generated.scala"),
          Seq("devx/src/atn/mill/CodeScene.scala", "core/src/**/*.scala", "!**/Generated.scala")
        )
      )
      cases.foreach { (roots, included, excluded, expected) =>
        val ps = StrykerModule.mutatePatterns(roots, included, excluded)
        assert(ps == expected)
        // stryker4s partitions `mutate` on the `!` prefix, so every exclude must carry exactly one.
        assert(ps.count(_.startsWith("!")) == excluded.size)
        assert(!ps.exists(_.startsWith("!!")))
        // No included files keeps the per-source-root behaviour of the two-argument form.
        if included.isEmpty then assert(ps == StrykerModule.mutatePatterns(roots, excluded))
      }
    }

    test("compiler artifact, main class and binary version - one per Scala major, one testrunner per Scala 2 minor") {
      // `scala3-compiler_3:2.13.16` does not exist, and resolving scala-compiler but invoking dotty.tools.dotc.Main
      // fails with "Could not find or load main class": the three must agree, or the run aborts before instrumenting.
      val cases = Seq(
        "3.8.4"   -> ("scala3-compiler_3", "dotty.tools.dotc.Main", "3"),
        "3.3.7"   -> ("scala3-compiler_3", "dotty.tools.dotc.Main", "3"),
        "2.13.16" -> ("scala-compiler", "scala.tools.nsc.Main", "2.13"),
        "2.12.20" -> ("scala-compiler", "scala.tools.nsc.Main", "2.12")
      )
      cases.foreach { case (version, (artifact, mainClass, binary)) =>
        assert(StrykerModule.compilerArtifactName(version) == artifact)
        assert(StrykerModule.compilerMainClass(version) == mainClass)
        assert(StrykerModule.scalaBinaryVersion(version) == binary)
      }
    }

    test("parseCompilerErrors - Scala 3 errors become one CompilerErrMsg each, path relative to the tmp source dir") {
      val tmp        = os.root / "ws" / "out" / "devx" / "strykerMutate.dest" / "target" / "stryker4s-123"
      val file       = tmp / "devx" / "src" / "atn" / "mill" / "DeveloperExperience.scala"
      val output     =
        s"""-- [E008] Not Found Error: $file:264:97 ------------------------------------
           |264 |        if (!os.forall(teamsPath)) Result.Failure(s"Teams file not found: $$teamsPath") else {
           |    |             ^^^^^^^^^
           |    |             value forall is not a member of os
           |-- Error: $file:87:25 -------------------------------------------------------
           |87 |        writeJson(dest / "", allSettings)
           |   |                         ^^
           |   |Exception occurred while executing macro expansion.
           |   |os.PathError$$InvalidSegment: [] is not a valid path segment.
           |2 errors found
           |""".stripMargin
      val errors     = StrykerModule.parseCompilerErrors(output, tmp)
      assert(
        errors.map(_.path) == Seq(
          "devx/src/atn/mill/DeveloperExperience.scala",
          "devx/src/atn/mill/DeveloperExperience.scala"
        )
      )
      assert(errors.map(_.line.intValue) == Seq(264, 87))
      assert(errors.head.msg == "value forall is not a member of os")
      assert(errors(1).msg == "Exception occurred while executing macro expansion.")
      // stryker4s's RollbackHandler matches `mutatedFile.fileOrigin.toString.endsWith(err.path)`; the origin is the
      // mirrored source under Task.dest, so the tmp-dir prefix must be gone and the rest kept verbatim.
      val fileOrigin = os.root / "ws" / "out" / "devx" / "strykerMutate.dest" / "devx" / "src" / "atn" / "mill" /
        "DeveloperExperience.scala"
      assert(errors.forall(e => fileOrigin.toString.endsWith(e.path)))
    }

    test("parseCompilerErrors - Scala 2 errors, ANSI colours and paths outside the tmp dir") {
      val tmp    = os.root / "tmp" / "s4s"
      val output =
        "\u001b[31m/tmp/s4s/app/src/Main.scala:12: error: not found: value foo\u001b[0m\n" +
          "  foo(1)\n  ^\n" +
          "/elsewhere/Other.scala:3: error: type mismatch;\n" +
          "one error found\n"
      val errors = StrykerModule.parseCompilerErrors(output, tmp)
      assert(
        errors.map(e => (e.path, e.line.intValue, e.msg)) == Seq(
          ("app/src/Main.scala", 12, "not found: value foo"),
          ("/elsewhere/Other.scala", 3, "type mismatch;")
        )
      )
    }

    test("parseCompilerErrors - warnings only or empty output yield no errors") {
      val tmp      = os.root / "tmp" / "s4s"
      assert(StrykerModule.parseCompilerErrors("", tmp).isEmpty)
      val warnings =
        s"""-- [E092] Pattern Match Unchecked Warning: ${tmp / "a" / "B.scala"}:4:2 ----
           |4 |  x match
           |  |  ^
           |1 warning found
           |""".stripMargin
      assert(StrykerModule.parseCompilerErrors(warnings, tmp).isEmpty)
    }

    test("filterScalacOptions - removes fatal warnings and unused") {
      val opts     = Seq("-Xfatal-warnings", "-deprecation", "-Wunused:all", "-Yexplicit-nulls")
      val filtered = StrykerModule.filterScalacOptions(opts)
      assert(filtered == Seq("-deprecation", "-Yexplicit-nulls"))
    }

    test("mirrorSources - copies only the .scala files, keeping the workspace-relative layout, one root per dir") {
      val ws       = os.temp.dir()
      val dest     = os.temp.dir()
      os.write(ws / "m" / "src" / "a" / "A.scala", "object A", createFolders = true)
      os.write(ws / "m" / "src" / "a" / "notes.md", "skip", createFolders = true)
      os.write(ws / "m" / "gen" / "B.scala", "object B", createFolders = true)
      os.makeDir.all(ws / "m" / "empty")
      val mirrored = StrykerModule.mirrorSources(Seq(ws / "m" / "src", ws / "m" / "gen", ws / "m" / "empty"), ws, dest)
      assert(mirrored == Seq(dest / "m" / "src", dest / "m" / "gen", dest / "m" / "empty"))
      val copied   = os.walk(dest).filter(os.isFile).map(_.relativeTo(dest).toString).sorted
      assert(copied == Seq("m/gen/B.scala", "m/src/a/A.scala"))
      assert(os.read(dest / "m" / "src" / "a" / "A.scala") == "object A")
      // A root without sources is still mirrored: stryker4s's mutate globs point at it.
      assert(os.isDir(dest / "m" / "empty"))
    }

    // One UnitTester scope for every task: a second scope over the same root module deletes the first one's out dir
    // while this JVM still holds a handle in it, which NFS turns into a `.nfsXXXX: Device or resource busy` failure.
    test(
      "Stryker4sModule - defaults, strykerConf, and the test module's forkEnv and forkArgs forwarded to the testrunner"
    ) {
      assert(TestStrykerBuild.strykerExcludedFiles.isEmpty)
      assert(TestStrykerBuild.strykerMutate().exclusive)
      UnitTester(TestStrykerBuild, os.temp.dir()).scoped { eval =>
        val Right(confResult) = eval(TestStrykerBuild.strykerConf): @unchecked
        val conf              = confResult.value
        assert(conf("scala-dialect").str == "scala3future")
        assert(conf("concurrency").num > 0)
        assert(conf("reporters").arr.map(_.str).contains("console"))
        val Right(envResult)  = eval(TestStrykerBuild.strykerTestRunnerEnv): @unchecked
        val env               = envResult.value
        // Mill's own test task adds MILL_TEST_RESOURCE_DIR on top of forkEnv; tests reading it (cpd's do) can only
        // run under mutation if the forked stryker testrunner sees the same map.
        assert(env("STRYKER_TEST_FLAG") == "on")
        assert(env("MILL_TEST_RESOURCE_DIR").nonEmpty)
        val Right(argsResult) = eval(TestStrykerBuild.strykerTestRunnerJvmArgs): @unchecked
        val args              = argsResult.value
        assert(args.indexOf("-Dstryker.test=1") < args.indexOf("-Xmx4G"))
        assert(args.last == "-Xmx4G")
        // Whole-module mutation by default: no included-file globs unless a build (or CI) narrows the scope.
        val Right(included)   = eval(TestStrykerBuild.strykerIncludedFiles): @unchecked
        assert(included.value.isEmpty)
      }
    }

/**
 * The shape every stryker test build shares: pinned versions and a bare utest module with fork settings of its own,
 * which the testrunner must inherit.
 */
trait TestStrykerModule extends Stryker4sModule:
  def scalaVersion      = "3.8.2"
  def strykerVersion    = "0.19.1"
  def strykerTestModule = test

  object test extends ScalaTests with TestModule.Utest:
    override def mvnDeps  = Seq.empty
    override def forkEnv  = Task(super.forkEnv() + ("STRYKER_TEST_FLAG" -> "on"))
    override def forkArgs = Task(super.forkArgs() :+ "-Dstryker.test=1")

object TestStrykerBuild extends TestRootModule with TestStrykerModule:
  lazy val millDiscover: Discover = Discover[this.type]

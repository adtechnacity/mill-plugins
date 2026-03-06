package atn.mill

import cats.effect.IO
import mutationtesting.MutantStatus
import stryker4s.model.*
import stryker4s.run.TestRunner
import stryker4s.testrunner.api.TestFile

import java.util.concurrent.TimeoutException
import scala.concurrent.duration.*
import scala.util.{Failure, Success, Try}

/**
 * TestRunner implementation for Mill that executes tests in a forked JVM.
 *
 * For each test run (initial or per-mutant), forks a lightweight JVM process that runs the test framework directly via
 * sbt.testing API. Mutations are activated via the `-DACTIVE_MUTATION=<id>` system property, matching stryker4s
 * `InstrumenterOptions.sysContext`.
 *
 * @param testClasspath
 *   JARs and class dirs for the test runtime classpath (includes compiled test + production classes)
 * @param testRunnerCp
 *   classpath entries for the test runner main class (compiled at runtime)
 * @param frameworkName
 *   fully-qualified sbt.testing.Framework class name (e.g. "utest.runner.Framework")
 * @param testClasses
 *   fully-qualified test class names to execute
 * @param testTimeout
 *   max duration per test run in milliseconds
 */
class MillTestRunner(
  testClasspath: Seq[os.Path],
  testRunnerCp: os.Path,
  frameworkName: String,
  testClasses: Seq[String],
  testTimeout: Long,
  mutatedClassDir: Option[os.Path] = None
) extends TestRunner:

  private val javaBin = os.Path(sys.props("java.home")) / "bin" / "java"

  override def initialTestRun(): IO[InitialTestRunResult] =
    runTests(activeMutation = None).map {
      case Success(0) => NoCoverageInitialTestRun(true)
      case _          => NoCoverageInitialTestRun(false)
    }

  override def runMutant(mutant: MutantWithId, testNames: Seq[TestFile]): IO[mutationtesting.MutantResult] =
    runTests(activeMutation = Some(mutant.id.toString)).map {
      case Success(0)                   => mutant.toMutantResult(MutantStatus.Survived)
      case Success(_)                   => mutant.toMutantResult(MutantStatus.Killed)
      case Failure(_: TimeoutException) => mutant.toMutantResult(MutantStatus.Timeout)
      case Failure(_)                   => mutant.toMutantResult(MutantStatus.RuntimeError)
    }

  private def runTests(activeMutation: Option[String]): IO[Try[Int]] = IO.blocking {
    // Prepend mutated class dir so instrumented classes shadow originals
    val cpEntries = mutatedClassDir.toSeq ++ (testRunnerCp +: testClasspath)
    val cp        = cpEntries.map(_.toString).mkString(java.io.File.pathSeparator)

    val jvmArgs = activeMutation.toList.flatMap(id => List(s"-DACTIVE_MUTATION=$id"))

    val cmd = os.proc(
      javaBin.toString +: jvmArgs :++ Seq("-cp", cp, "atn.mill.StrykerTestRunnerMain") :++ (frameworkName +: testClasses)
    )

    Try {
      val result = cmd.call(check = false, stdout = os.Inherit, stderr = os.Inherit, timeout = testTimeout)
      result.exitCode
    }.recoverWith {
      case e: os.SubprocessException if e.getMessage.contains("timed out") =>
        Failure(new TimeoutException(s"Test run timed out after ${testTimeout}ms"))
    }
  }

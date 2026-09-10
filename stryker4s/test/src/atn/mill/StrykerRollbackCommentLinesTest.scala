package atn.mill

import cats.data.NonEmptyList
import fs2.io.file.Path
import mutationtesting.MutantStatus
import stryker4s.config.Config
import stryker4s.model.{CompilerErrMsg, MutantId, MutantWithId, PlaceableTree, SourceContext}
import stryker4s.mutants.TreeTraverserImpl
import stryker4s.mutants.findmutants.MutantMatcherImpl
import stryker4s.mutants.tree.{InstrumenterOptions, MutantCollector, MutantInstrumenter, MutantsWithId, Mutations}
import stryker4s.run.RollbackHandler
import utest.*

import scala.meta.parsers.XtensionParseInputLike
import scala.meta.{dialects, Dialect, Source}

import StrykerTestSupport.given

/**
 * Regression for stryker-mutator/stryker4s#2011 (fixed by #2013, shipped in stryker4s-core 0.21.0): `MutantRunner`
 * wrote the instrumented file with the comment-keeping printer while `RollbackHandler` re-parsed `.syntax`, which drops
 * comments, so a compile error's line was looked up in a text where every comment line above it was missing and the
 * wrong mutant (or none) was rolled back. [[Stryker4sMillRunner]] reports plain `CompilerErrMsg(msg, path, line)` from
 * the scalac output of exactly the written text, so it depends on the two printers agreeing.
 */
object StrykerRollbackCommentLinesTest extends TestSuite:

  private given config: Config = Config.default.copy(scalaDialect = dialects.Scala3)
  private given Dialect        = config.scalaDialect

  /**
   * A scaladoc block on an untouched statement above the mutated body. Instrumentation rebuilds the mutated `def`
   * without its own comments, but a sibling it never touches is printed from its original text, comments included, by
   * the comment-keeping printer and without them by `.syntax`: six lines the mutation switch moves by.
   */
  private val fixture =
    """object Fixture {
      |  /**
      |   * The smallest positive value.
      |   *
      |   * Six comment lines above the mutated `def`: a printer that keeps them puts the mutation switch six lines
      |   * further down than one that drops them.
      |   */
      |  val limit: Int = 1
      |  def anyPositive(xs: List[Int]): Boolean = xs.exists(_ > 0)
      |}
      |""".stripMargin

  private val origin = Path("/ws/src/Fixture.scala")

  /** What `Mutator.updateWithId` does for a single file: number the mutants in encounter order from 0. */
  private def withIds(found: Map[PlaceableTree, Mutations]): Map[PlaceableTree, MutantsWithId] =
    found.toVector
      .foldLeft((0, Map.empty[PlaceableTree, MutantsWithId])) { case ((next, acc), (tree, mutations)) =>
        val numbered = mutations.zipWithIndex.map { case (code, i) => MutantWithId(MutantId(next + i), code) }
        (next + mutations.length, acc.updated(tree, numbered))
      }
      ._2

  val tests = Tests:

    test("rollback marks the mutant on the compile error's line of the comment-keeping text stryker4s compiles") {
      val source           = fixture.parse[Source].get
      val (ignored, found) = new MutantCollector(new TreeTraverserImpl(), new MutantMatcherImpl())(source)
      assert(ignored.isEmpty)
      val instrumenter     = new MutantInstrumenter(InstrumenterOptions.testRunner)
      val mutatedFile      = instrumenter.instrumentFile(SourceContext(source, origin), withIds(found))

      // The text MutantRunner.writeMutatedFile hands to scalac: the reprint keeps the sibling's scaladoc.
      val compiled = mutatedFile.mutatedSource.printSyntaxFor(config.scalaDialect)
      assert(compiled.contains("The smallest positive value."))

      // The `exists` -> `forall` mutant, and the 1-based line scalac reports it on in that text.
      val forall     = mutatedFile.mutants.find(_.mutatedCode.metadata.replacement.contains("forall")).get
      val forallLine = compiled.linesIterator.indexWhere(_.contains("xs.forall(")) + 1
      assert(forallLine > 0)
      val error      = CompilerErrMsg("value forall is not a member of List[Int]", "src/Fixture.scala", forallLine)

      val rolledBack    = RollbackHandler(instrumenter).rollbackFiles(NonEmptyList.one(error), Vector(mutatedFile))
      assert(rolledBack.isRight)
      val Right(result) = rolledBack: @unchecked

      // Exactly the forall mutant is the compile error; every other mutant stays in the file to be tested.
      val compileErrors = result.compileErrors.getOrElse(origin, Vector.empty)
      assert(compileErrors.map(_.id) == Vector(forall.id.toString))
      assert(compileErrors.map(_.status) == Vector(MutantStatus.CompileError))
      val remaining     = result.newFiles.flatMap(_.mutants.toVector.map(_.id)).toSet
      assert(remaining == mutatedFile.mutants.toVector.map(_.id).toSet - forall.id)
    }

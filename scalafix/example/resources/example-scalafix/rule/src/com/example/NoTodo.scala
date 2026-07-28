package com.example

import scala.meta._
import scalafix.v1._

/** Syntactic Scalafix rule that lints uses of `???` in source code. */
class NoTodo extends SyntacticRule("NoTodo") {
  override def fix(implicit doc: SyntacticDocument): Patch =
    doc.tree.collect { case t @ Term.Name("???") => Patch.lint(NoTodoDiagnostic(t)) }.asPatch
}

final case class NoTodoDiagnostic(tree: Tree) extends Diagnostic {
  override def message: String               = "Replace `???` with a real implementation"
  override def position: scala.meta.Position = tree.pos
}

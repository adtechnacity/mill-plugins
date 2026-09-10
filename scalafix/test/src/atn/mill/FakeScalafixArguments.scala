package atn.mill

import scalafix.interfaces.*

import java.io.PrintStream
import java.net.{URL, URLClassLoader}
import java.nio.charset.Charset
import java.nio.file.{Path, PathMatcher}
import java.util.{Collections, List => JList, Optional}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/**
 * A recording `ScalafixArguments` standing in for the tool-classloader arguments that
 * `Scalafix.fetchAndClassloadInstance` would build: every builder call [[ScalafixSupport]] makes returns a copy
 * carrying its value, `run()` reports `errors`, `rulesThatWillRun` has `rules` entries and `validate()` yields
 * `validation`. Nothing is fetched or classloaded. The builder calls ScalafixSupport never makes are unsupported.
 */
final case class FakeScalafixArguments(
  errors: Seq[ScalafixError] = Seq.empty,
  rules: Int = 0,
  validation: Option[ScalafixException] = None,
  parsedArguments: Seq[String] = Seq.empty,
  workingDirectory: Option[Path] = None,
  config: Option[Path] = None,
  classpath: Seq[Path] = Seq.empty,
  scalaVersion: Option[String] = None,
  scalacOptions: Seq[String] = Seq.empty,
  paths: Seq[Path] = Seq.empty
) extends ScalafixArguments:

  def withParsedArguments(args: JList[String]): ScalafixArguments  = copy(parsedArguments = args.asScala.toSeq)
  def withWorkingDirectory(path: Path): ScalafixArguments          = copy(workingDirectory = Some(path))
  def withConfig(path: Optional[Path]): ScalafixArguments          = copy(config = path.toScala)
  def withClasspath(entries: JList[Path]): ScalafixArguments       = copy(classpath = entries.asScala.toSeq)
  def withScalaVersion(version: String): ScalafixArguments         = copy(scalaVersion = Some(version))
  def withScalacOptions(options: JList[String]): ScalafixArguments = copy(scalacOptions = options.asScala.toSeq)
  def withPaths(sources: JList[Path]): ScalafixArguments           = copy(paths = sources.asScala.toSeq)
  def run(): Array[ScalafixError]                                  = errors.toArray
  def rulesThatWillRun(): JList[ScalafixRule]                      = Collections.nCopies(rules, FakeScalafixArguments.Rule)
  def validate(): Optional[ScalafixException]                      = validation.toJava

  private def unsupported: Nothing = throw new UnsupportedOperationException("not used by ScalafixSupport")

  def withRules(names: JList[String]): ScalafixArguments                          = unsupported
  def withToolClasspath(loader: URLClassLoader): ScalafixArguments                = unsupported
  def withToolClasspath(urls: JList[URL]): ScalafixArguments                      = unsupported
  def withToolClasspath(urls: JList[URL], deps: JList[String]): ScalafixArguments = unsupported
  def withToolClasspath(
    urls: JList[URL],
    deps: JList[String],
    repositories: JList[coursierapi.Repository]
  ): ScalafixArguments = unsupported
  def withExcludedPaths(matchers: JList[PathMatcher]): ScalafixArguments          = unsupported
  def withMode(mode: ScalafixMainMode): ScalafixArguments                         = unsupported
  def withPrintStream(out: PrintStream): ScalafixArguments                        = unsupported
  def withSourceroot(path: Path): ScalafixArguments                               = unsupported
  def withSemanticdbTargetroots(targetroots: JList[Path]): ScalafixArguments      = unsupported
  def withMainCallback(callback: ScalafixMainCallback): ScalafixArguments         = unsupported
  def withCharset(charset: Charset): ScalafixArguments                            = unsupported
  def availableRules(): JList[ScalafixRule]                                       = unsupported
  def evaluate(): ScalafixEvaluation                                              = unsupported

object FakeScalafixArguments:

  /** The one rule [[FakeScalafixArguments.rulesThatWillRun]] repeats; only the count matters to ScalafixSupport. */
  object Rule extends ScalafixRule:
    def name(): String            = "FakeRule"
    def description(): String     = "A rule that never runs"
    def kind(): ScalafixRuleKind  = ScalafixRuleKind.SYNTACTIC
    def isLinter(): Boolean       = true
    def isRewrite(): Boolean      = false
    def isExperimental(): Boolean = false

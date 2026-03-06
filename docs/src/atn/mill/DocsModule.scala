package atn.mill

import mill._
import mill.contrib.scoverage.ScoverageModule
import mill.scalalib._

trait DocsModule extends DefaultTaskModule {

  /** Project name used in the generated scaladoc site. */
  def docProjectName: String

  /** Current project version string for the scaladoc site. */
  def docVersion: T[String]

  /** Root module from which to discover all ScalaModules. */
  def docRootModule: Module

  // Exclude docRootModule from this module's reflected children to prevent a
  // circular reference: docRootModule points back to an ancestor, and without
  // this filter Mill's recursive module traversal (moduleInternal.modules,
  // GitHooksModule.validModules, etc.) would loop infinitely.
  override def moduleDirectChildren: Seq[Module] =
    super.moduleDirectChildren.filterNot(_ eq docRootModule)

  /** Module segment paths to exclude from scaladoc generation. */
  def excludedModules: Set[String] = Set.empty

  /** Optional source-links argument for deployment builds. */
  def sourceLinks: Seq[String] = Seq.empty

  /** Root-relative paths to markdown files to transform into static doc pages. */
  def staticDocSources: Seq[os.RelPath] = Seq.empty

  /** Declared source for the hand-authored docs/ directory. */
  def docsSiteRoot: T[PathRef] = Task.Source(docRootModule.moduleDir / "docs")

  /** Declared sources for each static doc file so Mill tracks them. */
  def staticDocSourcePaths: T[Seq[PathRef]] = Task.Sources(staticDocSources.map(docRootModule.moduleDir / _)*)

  /** Prepare the siteroot by merging hand-authored docs/ with transformed sources. */
  def preparedSiteRoot: T[PathRef] = Task {
    val staged    = Task.dest / "site-root"
    val docsDir   = docsSiteRoot().path
    // Copy the entire docs/ directory as the base
    if (os.exists(docsDir)) os.copy(docsDir, staged)
    else os.makeDir.all(staged / "_docs")
    // Transform each static doc source into the staged _docs/
    val targetDir = staged / "_docs"
    os.makeDir.all(targetDir)
    for (sourceRef <- staticDocSourcePaths()) {
      val result = DocTransformer.transform(sourceRef.path, docProjectName, targetDir)
      result.warnings.foreach(w => Task.log.error(s"[DocTransformer] $w"))
    }
    PathRef(staged)
  }

  /** Resolves the final list of modules to be included in the documentation. */
  def allModules: Seq[ScalaModule] = docRootModule.moduleInternal.modules.collect {
    case m: ScalaModule
        if !m.isInstanceOf[mill.javalib.TestModule]
        && !m.isInstanceOf[ScoverageModule#ScoverageData]
        && !excludedModules(m.moduleSegments.render) =>
      m
  }

  /** List all modules included in scaladoc generation. */
  def listModules() = Task.Command {
    allModules.map(_.moduleSegments.render).sorted.foreach(println)
  }

  /** Generate unified scaladoc site for local browsing. */
  def local: T[PathRef] = Task {
    val dest = Task.dest / "site"
    os.makeDir.all(dest)
    runScaladoc(dest, classDirs(), classpath(), docCp(), docVersion(), preparedSiteRoot().path)
    PathRef(dest)
  }

  def defaultTask(): String = "local"

  /** Generate unified scaladoc site for deployment with source links. */
  def site: T[PathRef] = Task {
    val dest = Task.dest / "site"
    os.makeDir.all(dest)
    runScaladoc(dest, classDirs(), classpath(), docCp(), docVersion(), preparedSiteRoot().path, sourceLinks)
    PathRef(dest)
  }

  def runScaladoc(
    dest: os.Path,
    classDirs: Seq[os.Path],
    classpath: Seq[os.Path],
    docCp: Seq[os.Path],
    version: String,
    siteRoot: os.Path,
    extraOpts: Seq[String] = Seq.empty
  ): Unit = {
    val args = Seq(
      "-d",
      dest.toString,
      "-project",
      docProjectName,
      "-project-version",
      version,
      "-siteroot",
      siteRoot.toString,
      "-classpath",
      classpath.mkString(java.io.File.pathSeparator),
      "-no-link-warnings"
    ) ++ extraOpts ++ classDirs.map(_.toString)

    val javaBin = os.Path(sys.props("java.home")) / "bin" / "java"
    os.proc(javaBin, "-cp", docCp.mkString(java.io.File.pathSeparator), "dotty.tools.scaladoc.Main", args)
      .call(cwd = docRootModule.moduleDir, stdout = os.Inherit, stderr = os.Inherit)
  }

  private def classDirs: T[Seq[os.Path]] = Task(Task.traverse(allModules)(_.compile)().map(_.classes.path))
  private def classpath: T[Seq[os.Path]] = Task {
    Task.traverse(allModules)(_.compileClasspath)().flatten.map(_.path).distinct
  }
  private def docCp: T[Seq[os.Path]]     = Task {
    Task.traverse(allModules)(_.scalaDocClasspath)().flatten.map(_.path).distinct
  }

}

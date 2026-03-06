package atn.mill

import cats.data.NonEmptyVector

/** Result of transforming a markdown file into scaladoc-ready pages. */
case class TransformResult(files: Seq[os.Path], warnings: Seq[String])

object DocTransformer:

  private def baseName(filename: String): String =
    filename.stripSuffix(".md").stripSuffix(".MD")

  /** Derive a page title from a markdown filename using conventions. */
  def titleFromFilename(filename: String, projectName: String): String =
    val base = baseName(filename)
    if base.equalsIgnoreCase("readme") then projectName
    else
      base
        .split("[_\\-]+")
        .filter(_.nonEmpty)
        .map(w => w.head.toUpper +: w.tail.toLowerCase)
        .mkString(" ")

  /** Derive the target filename for a markdown source. */
  def targetFilename(filename: String): String =
    val base = baseName(filename)
    if base.equalsIgnoreCase("readme") then "index.md"
    else base.toLowerCase.replace('_', '-') + ".md"

  /** Convert a title to a URL-friendly slug. */
  def slugify(title: String): String =
    title.toLowerCase
      .replaceAll("[^a-z0-9\\s-]", "")
      .trim
      .replaceAll("[\\s-]+", "-")

  /** Compute multiple pages from split marker sections. */
  private def splitPages(
    sections: NonEmptyVector[(FrontMatter, String)],
    filename: String,
    projectName: String,
    targetDir: os.Path
  ): NonEmptyVector[(os.Path, String)] =
    val firstTarget  = targetDir / targetFilename(filename)
    val firstContent = FrontMatter("title" -> titleFromFilename(filename, projectName)).render + sections.head._2

    val rest = sections.tail.map: (fm, sectionContent) =>
      val sectionTitle =
        fm.get("title").getOrElse(throw new IllegalArgumentException(s"Split marker missing 'title' in $filename"))
      val sectionFile  = targetDir / (slugify(sectionTitle) + ".md")
      sectionFile -> (fm.render + sectionContent)

    NonEmptyVector(firstTarget -> firstContent, rest.toVector)

  /** Transform a root markdown file into one or more scaladoc-ready pages. */
  def transform(source: os.Path, projectName: String, targetDir: os.Path): TransformResult =
    require(os.exists(source), s"Static doc source not found: $source")
    val content  = os.read(source)
    val filename = source.last
    val warnings = ImageAdjuster.copyImages(content, source / os.up, targetDir, filename)
    val sections = SplitMarker.splitDocument(content)
    val pages    = splitPages(sections, filename, projectName, targetDir)

    pages.toVector.foreach((path, rendered) => os.write.over(path, rendered))
    TransformResult(pages.map(_._1).toVector, warnings)

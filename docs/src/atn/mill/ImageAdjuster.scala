package atn.mill

object ImageAdjuster:

  private val ImagePattern = """!\[([^\]]*)\]\(([^)]+)\)""".r

  /** Extract relative image paths from markdown content. */
  def extractImagePaths(content: String): Seq[String] =
    ImagePattern
      .findAllMatchIn(content)
      .map(_.group(2))
      .filter(p => !p.startsWith("http://") && !p.startsWith("https://") && !p.startsWith("/"))
      .toSeq

  /** Copy images referenced in content to the target directory, returning warnings for missing ones. */
  def copyImages(content: String, sourceDir: os.Path, targetDir: os.Path, filename: String): Seq[String] =
    val (found, missing) = extractImagePaths(content).partition: imgPath =>
      os.exists(sourceDir / os.RelPath(imgPath))

    for imgPath <- found do
      val imgTarget = targetDir / os.RelPath(imgPath)
      os.makeDir.all(imgTarget / os.up)
      os.copy.over(sourceDir / os.RelPath(imgPath), imgTarget)

    missing.map(p => s"image not found: ${sourceDir / os.RelPath(p)} (referenced in $filename)")

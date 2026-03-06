package atn.mill

import cats.data.NonEmptyVector

/** YAML frontmatter fields parsed from split marker comments or derived from filename conventions. */
object FrontMatter:
  opaque type FrontMatter = Map[String, String]

  val empty: FrontMatter                                = Map.empty
  def apply(fields: (String, String)*): FrontMatter     = Map(fields*)
  def fromMap(fields: Map[String, String]): FrontMatter = fields
  extension (fm: FrontMatter)
    def fields: Map[String, String]      = fm
    def get(key: String): Option[String] = fm.get(key)
    def isEmpty: Boolean                 = fm.isEmpty
    def render: String                   =
      val body = fm.toSeq.sortBy(_._1).map((k, v) => s"$k: $v").mkString("\n")
      s"---\n$body\n---\n\n"

export FrontMatter.FrontMatter

/** Result of parsing a line for a split marker comment. */
enum SplitMarker:
  case NoMarker
  case Marker(fields: FrontMatter)

object SplitMarker:

  private val SplitMarkerPattern = """<!--\s*split:\s*(.+?)\s*-->""".r
  private val KeyValue           = """([^:]+):(.+)""".r

  /** Parse a line as a split marker comment. */
  def parse(line: String): SplitMarker =
    line.trim match
      case SplitMarkerPattern(fields) =>
        Marker(
          FrontMatter.fromMap(
            fields
              .split(",")
              .collect { case KeyValue(k, v) => k.trim -> v.trim }
              .toMap
          )
        )
      case _                          => NoMarker

  /**
   * Split markdown content on `<!-- split: ... -->` markers. Returns a non-empty sequence of (frontmatter, content)
   * pairs. The first section has empty frontmatter (it inherits the file-level frontmatter).
   */
  def splitDocument(content: String): NonEmptyVector[(FrontMatter, String)] =
    val lines   = content.linesIterator.toVector
    val markers = lines.zipWithIndex
      .map((line, idx) => parse(line) -> idx)
      .collect { case (Marker(fields), idx) => idx -> fields }

    if markers.isEmpty then NonEmptyVector.one(FrontMatter.empty -> content)
    else
      val boundaries = markers.map(_._1) :+ lines.length
      val first      = FrontMatter.empty -> lines.take(boundaries.head).mkString("", "\n", "\n")
      val rest       = markers.zip(boundaries.tail).map { case ((markerIdx, fm), endLine) =>
        fm -> lines.slice(markerIdx + 1, endLine).mkString("", "\n", "\n")
      }
      NonEmptyVector.fromVectorUnsafe((first +: rest).toVector)

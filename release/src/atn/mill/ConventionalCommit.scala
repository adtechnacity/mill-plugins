package atn.mill

/** Parsed conventional commit. */
case class ConventionalCommit(hash: String, typ: String, scope: Option[String], breaking: Boolean, description: String)

object ConventionalCommit:

  private val FirstLineRE = """^(\w+)(?:\(([\w\s,-]+)\))?(!)?:\s(.+)$""".r

  /** Parse a conventional commit from its hash and full message. */
  def parse(hash: String, message: String): Option[ConventionalCommit] =
    message.linesIterator
      .nextOption()
      .flatMap:
        case FirstLineRE(typ, scope, bang, desc) =>
          Some(ConventionalCommit(hash, typ, Option(scope), bang != null, desc.trim))
        case _                                   => None

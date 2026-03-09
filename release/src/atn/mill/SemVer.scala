package atn.mill

/** Parsed semantic version (major.minor.patch). */
case class SemVer(major: Int, minor: Int, patch: Int):
  def bumpMajor: SemVer = SemVer(major + 1, 0, 0)
  def bumpMinor: SemVer = SemVer(major, minor + 1, 0)
  def bumpPatch: SemVer = SemVer(major, minor, patch + 1)
  def release: String   = s"$major.$minor.$patch"
  def snapshot: String  = s"$major.$minor.$patch-SNAPSHOT"

object SemVer:

  /** Parse a version string, stripping optional "v" prefix and "-SNAPSHOT" suffix. */
  def parse(s: String): Option[SemVer] =
    val cleaned = s.trim.stripPrefix("v").stripSuffix("-SNAPSHOT")
    cleaned.split('.') match
      case Array(ma, mi, pa) =>
        for
          major <- ma.toIntOption
          minor <- mi.toIntOption
          patch <- pa.toIntOption
        yield SemVer(major, minor, patch)
      case _                 => None

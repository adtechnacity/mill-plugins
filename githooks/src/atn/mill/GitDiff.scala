package atn.mill

import org.eclipse.jgit.diff.{DiffEntry, DiffFormatter, RawText}
import org.eclipse.jgit.dircache.{DirCache, DirCacheIterator}
import org.eclipse.jgit.lib.{ObjectId, Repository}
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.{AbstractTreeIterator, CanonicalTreeParser, EmptyTreeIterator}
import org.eclipse.jgit.util.io.NullOutputStream

import scala.jdk.CollectionConverters._
import scala.util.Try

/** One line of a diff: 1-based line number in its file (new file for additions, old file for removals). */
final case class DiffLine(number: Int, text: String)

/**
 * A file's view of a diff relative to one parent: the parent-side path, the lines removed relative to that parent
 * (old-file numbering), and lazy access to the parent's full content (used to resolve test-case regions).
 */
final case class ParentLeg(oldPath: String, removed: Vector[DiffLine], oldContent: () => String)

/**
 * One changed file in a change-set. For ordinary diffs there is at most one [[ParentLeg]]; for merge commits there is
 * one per parent in which the file existed, and [[removed]] is the combined view (present in all parents, absent from
 * the result).
 */
final case class ChangedFile(newPath: Option[String], legs: Vector[ParentLeg], binary: Boolean, added: Vector[DiffLine]) {
  def oldPath: Option[String] = legs.headOption.map(_.oldPath)

  /** Best display path: result-side when the file still exists, parent-side otherwise. */
  def path: String = newPath.orElse(oldPath).getOrElse("")

  /** Combined removed lines: for merges, only lines every leg removed (numbering from the first leg). */
  def removed: Vector[DiffLine] = legs match {
    case Vector()    => Vector.empty
    case Vector(one) => one.removed
    case many        =>
      val everywhere = many.map(_.removed.map(_.text).toSet).reduce(_ intersect _)
      many.head.removed.filter(l => everywhere(l.text))
  }
}

/**
 * Mill-API-free git diff plumbing for the signing conditions (owned by the conditional-commit-signing plan; the TSA
 * plan extends this file when it lands). Diffs are computed from trees and index state, so working-tree
 * `.gitattributes` filters never apply; binary-ness is content-sniffed, and binary files carry no lines — the
 * conditions treat that as fail-closed.
 */
object GitDiff {

  type ChangeSet = Vector[ChangedFile]

  /**
   * The staged diff (index vs HEAD tree, or vs the empty tree in a repo with no HEAD yet). `indexFile` supplies an
   * alternate index — the pre-commit hook passes `$GIT_INDEX_FILE` so `git commit -a`/pathspec temporary indexes are
   * honored.
   */
  def stagedChanges(repo: Repository, indexFile: Option[os.Path] = None): Either[String, ChangeSet] =
    wrap {
      val index                         = indexFile match {
        case Some(f) => DirCache.read(f.toIO, repo.getFS)
        case None    => repo.readDirCache()
      }
      val headTree                      = Option(repo.resolve("HEAD^{tree}"))
      def oldIter: AbstractTreeIterator = headTree match {
        case Some(tree) => treeParser(repo, tree)
        case None       => new EmptyTreeIterator()
      }
      scanLegs(repo, oldIter, new DirCacheIterator(index)).map(toChangedFile)
    }

  /** A commit's changes relative to its first parent (or the empty tree for a root commit). */
  def commitChanges(repo: Repository, commitId: ObjectId): Either[String, ChangeSet] =
    wrap {
      val walk = new RevWalk(repo)
      try {
        val commit                        = walk.parseCommit(commitId)
        def oldIter: AbstractTreeIterator =
          if (commit.getParentCount == 0) new EmptyTreeIterator()
          else treeParser(repo, walk.parseCommit(commit.getParent(0)).getTree)
        scanLegs(repo, oldIter, treeParser(repo, commit.getTree)).map(toChangedFile)
      } finally walk.close()
    }

  /**
   * A merge commit's combined change-set: added lines are present in the result and absent from all parents; removed
   * lines are present in all parents and absent from the result. Files changed relative to only some parents carry
   * nothing new (that content arrived on a branch and was verified there) and are excluded. Non-merge commits fall back
   * to [[commitChanges]].
   */
  def mergeCombinedChanges(repo: Repository, commitId: ObjectId): Either[String, ChangeSet] =
    wrap {
      val walk = new RevWalk(repo)
      try {
        val commit = walk.parseCommit(commitId)
        if (commit.getParentCount < 2)
          commitChanges(repo, commitId).fold(sys.error, identity)
        else {
          val perParent = commit.getParents.toVector.map { p =>
            scanLegs(repo, treeParser(repo, walk.parseCommit(p).getTree), treeParser(repo, commit.getTree))
          }
          combine(perParent)
        }
      } finally walk.close()
    }

  /** Raw per-entry data for one diff leg. */
  final private case class RawChange(
    entry: DiffEntry,
    added: Vector[DiffLine],
    removed: Vector[DiffLine],
    binary: Boolean,
    oldContent: () => String
  ) {
    def existedBefore: Boolean = entry.getChangeType != DiffEntry.ChangeType.ADD
    def existsAfter: Boolean   = entry.getChangeType != DiffEntry.ChangeType.DELETE
    def key: String            = if (existsAfter) entry.getNewPath else entry.getOldPath
    def leg: Option[ParentLeg] = Option.when(existedBefore)(ParentLeg(entry.getOldPath, removed, oldContent))
  }

  private def scanLegs(
    repo: Repository,
    oldIter: AbstractTreeIterator,
    newIter: AbstractTreeIterator
  ): Vector[RawChange] = {
    val fmt = new DiffFormatter(NullOutputStream.INSTANCE)
    try {
      fmt.setRepository(repo)
      fmt.setDetectRenames(true)
      fmt.scan(oldIter, newIter).asScala.toVector.map { entry =>
        def bytes(id: org.eclipse.jgit.lib.AbbreviatedObjectId): Array[Byte] =
          repo.open(id.toObjectId).getCachedBytes(BinaryThreshold)
        val oldBytes                                                         = Option.when(entry.getChangeType != DiffEntry.ChangeType.ADD)(bytes(entry.getOldId))
        val newBytes                                                         = Option.when(entry.getChangeType != DiffEntry.ChangeType.DELETE)(bytes(entry.getNewId))
        val binary                                                           = oldBytes.exists(RawText.isBinary) || newBytes.exists(RawText.isBinary)
        if (binary)
          RawChange(entry, Vector.empty, Vector.empty, binary = true, () => "")
        else {
          val oldText                                  = new RawText(oldBytes.getOrElse(Array.emptyByteArray))
          val newText                                  = new RawText(newBytes.getOrElse(Array.emptyByteArray))
          val edits                                    = fmt.toFileHeader(entry).toEditList.asScala.toVector
          def lines(t: RawText, from: Int, until: Int) =
            (from until until).map(i => DiffLine(i + 1, t.getString(i))).toVector
          val added                                    = edits.flatMap(e => lines(newText, e.getBeginB, e.getEndB))
          val removed                                  = edits.flatMap(e => lines(oldText, e.getBeginA, e.getEndA))
          RawChange(
            entry,
            added,
            removed,
            binary = false,
            () => new String(oldBytes.getOrElse(Array.emptyByteArray), "UTF-8")
          )
        }
      }
    } finally fmt.close()
  }

  private def toChangedFile(rc: RawChange): ChangedFile =
    ChangedFile(
      newPath = Option.when(rc.existsAfter)(rc.entry.getNewPath),
      legs = rc.leg.toVector,
      binary = rc.binary,
      added = rc.added
    )

  private def combine(perParent: Vector[Vector[RawChange]]): ChangeSet = {
    val byKey = perParent.map(_.map(rc => rc.key -> rc).toMap)
    // A file only carries combined content when it changed relative to EVERY parent.
    val keys  = byKey.map(_.keySet).reduce(_ intersect _)
    keys.toVector.sorted.flatMap { key =>
      val legsRaw       = byKey.map(_(key))
      val binary        = legsRaw.exists(_.binary)
      // Added lines share result-side numbering, so intersect on (number, text) across all legs.
      val combinedAdded = legsRaw
        .map(_.added.map(l => (l.number, l.text)).toSet)
        .reduce(_ intersect _)
        .toVector
        .sortBy(_._1)
        .map { case (n, t) => DiffLine(n, t) }
      // Removed lines combine only when the file existed in every parent (present in ALL parents).
      val legs          = if (legsRaw.forall(_.existedBefore)) legsRaw.flatMap(_.leg) else Vector.empty
      val file          = ChangedFile(
        newPath = Option.when(legsRaw.head.existsAfter)(legsRaw.head.entry.getNewPath),
        legs = legs,
        binary = binary,
        added = combinedAdded
      )
      Option.when(file.added.nonEmpty || file.removed.nonEmpty || binary)(file)
    }
  }

  private def treeParser(repo: Repository, tree: ObjectId): CanonicalTreeParser = {
    val reader = repo.newObjectReader()
    try {
      val p = new CanonicalTreeParser()
      p.reset(reader, tree)
      p
    } finally reader.close()
  }

  private def wrap[A](body: => A): Either[String, A] =
    Try(body).toEither.left.map(e => s"${e.getClass.getSimpleName}: ${e.getMessage}")

  /** Blobs larger than this load via getCachedBytes' limit; also the practical text-diff ceiling. */
  private val BinaryThreshold = 50 * 1024 * 1024
}

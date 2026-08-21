package atn.mill

import org.bouncycastle.openpgp._
import org.bouncycastle.openpgp.jcajce.JcaPGPObjectFactory
import org.bouncycastle.openpgp.operator.jcajce.{JcaKeyFingerprintCalculator, JcaPGPContentVerifierBuilderProvider}

import org.eclipse.jgit.lib.{ObjectId, Repository}
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.PathFilter

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

import scala.jdk.CollectionConverters._
import scala.util.Try

/** The outcome of checking one commit's OpenPGP signature against a [[TrustedKeys]] allowlist. */
sealed trait SigningVerdict

object SigningVerdict {
  case object Unsigned                            extends SigningVerdict
  final case class Unverifiable(format: String)   extends SigningVerdict
  final case class Invalid(reason: String)        extends SigningVerdict
  final case class Untrusted(fingerprint: String) extends SigningVerdict
  final case class Revoked(fingerprint: String)   extends SigningVerdict
  final case class Expired(fingerprint: String)   extends SigningVerdict
  final case class Trusted(fingerprint: String)   extends SigningVerdict
}

/**
 * One key loaded from a trust source: its full 40-hex fingerprint and whether it (or, for a subkey, its binding)
 * carries a valid revocation.
 */
final private case class LoadedKey(publicKey: PGPPublicKey, fingerprint: String, revoked: Boolean)

/**
 * Armored public keys loaded from a worktree directory or a git ref's tree, indexed by OpenPGP key ID for signature
 * lookup. Keys carrying a valid embedded revocation are retained (so a signature from one classifies as
 * [[SigningVerdict.Revoked]] rather than [[SigningVerdict.Untrusted]]) but excluded from [[trustedFingerprints]]. Known
 * shared-platform signing keys (e.g. a forge's web-flow key) are refused at load time unless explicitly allowed —
 * trusting them would collapse attribution to "has push access".
 */
final class TrustedKeys private (private val byKeyId: Map[Long, Vector[LoadedKey]]) {
  private[mill] def candidates(keyId: Long): Vector[LoadedKey] = byKeyId.getOrElse(keyId, Vector.empty)

  /** Full fingerprints of every loaded key/subkey that is not revoked. */
  def trustedFingerprints: Set[String] = byKeyId.values.flatten.filterNot(_.revoked).map(_.fingerprint).toSet
}

object TrustedKeys {

  /**
   * GitHub's published web-flow signing key, identified by its 16-hex long key ID (the fingerprint GitHub publishes).
   * Best-effort refusal check only — never used for trust matching, which is always full-fingerprint.
   */
  val KnownPlatformKeyIds: Set[String] = Set("B5690EEEBB952194")

  /** Load every regular file in `dir` as an armored key source. */
  def fromDirectory(dir: os.Path, allowKnownPlatformKeys: Boolean = false): Either[String, TrustedKeys] =
    if (!os.exists(dir) || !os.isDir(dir)) Left(s"trusted-keys configuration error: directory not found: $dir")
    else {
      val files = os.list(dir).filter(os.isFile)
      if (files.isEmpty) Left(s"trusted-keys configuration error: no key files found in $dir")
      else parseAndBuild(files.map(f => f.last -> os.read.bytes(f)), allowKnownPlatformKeys)
    }

  /**
   * Load every blob under `path` in `refName`'s tree as an armored key source — never the working tree or an untrusted
   * ref.
   */
  def fromRef(
    repo: Repository,
    refName: String,
    path: String,
    allowKnownPlatformKeys: Boolean = false
  ): Either[String, TrustedKeys] =
    Option(repo.resolve(s"$refName^{tree}")) match {
      case None         => Left(s"trusted-keys configuration error: ref not found: $refName")
      case Some(treeId) =>
        val walk = new TreeWalk(repo)
        try {
          walk.addTree(treeId)
          walk.setRecursive(true)
          walk.setFilter(PathFilter.create(path))
          val reader = repo.newObjectReader()
          try {
            val files = Iterator
              .continually(walk.next())
              .takeWhile(identity)
              .map(_ => walk.getPathString -> reader.open(walk.getObjectId(0)).getBytes)
              .toVector
            if (files.isEmpty) Left(s"trusted-keys configuration error: no key files found at $refName:$path")
            else parseAndBuild(files, allowKnownPlatformKeys)
          } finally reader.close()
        } finally walk.close()
    }

  private def parseAndBuild(
    sources: Seq[(String, Array[Byte])],
    allowKnownPlatformKeys: Boolean
  ): Either[String, TrustedKeys] = {
    val calc = new JcaKeyFingerprintCalculator()
    sources
      .foldLeft[Either[String, Vector[PGPPublicKeyRing]]](Right(Vector.empty)) { case (acc, (name, bytes)) =>
        acc.flatMap { rings =>
          Try {
            val in = PGPUtil.getDecoderStream(new ByteArrayInputStream(bytes))
            new PGPPublicKeyRingCollection(in, calc).getKeyRings.asScala.toVector
          }.toEither.left
            .map(e => s"corrupt armored key file $name: ${e.getMessage}")
            .map(rings ++ _)
        }
      }
      .flatMap(build(_, allowKnownPlatformKeys))
  }

  /**
   * `knownPlatformKeyIds` is a test seam — production callers always get [[KnownPlatformKeyIds]] via
   * [[fromDirectory]]/[[fromRef]].
   */
  private[mill] def build(
    rings: Vector[PGPPublicKeyRing],
    allowKnownPlatformKeys: Boolean,
    knownPlatformKeyIds: Set[String] = KnownPlatformKeyIds
  ): Either[String, TrustedKeys] = {
    val allKeys = rings.flatMap(_.getPublicKeys.asScala.toVector)
    val refused = allKeys.filter(k => knownPlatformKeyIds.contains(f"${k.getKeyID}%016X"))
    if (refused.nonEmpty && !allowKnownPlatformKeys)
      Left(
        "refusing to load known shared platform signing key(s): " +
          refused.map(k => f"${k.getKeyID}%016X").mkString(", ") +
          " — trusting these collapses attribution to \"has push access\"; pass allowKnownPlatformKeys=true to override"
      )
    else {
      val loaded = rings.flatMap { ring =>
        val master = ring.getPublicKey
        ring.getPublicKeys.asScala.toVector.map { key =>
          LoadedKey(key, fingerprintHex(key), revoked = isRevoked(key, master))
        }
      }
      Right(new TrustedKeys(loaded.groupBy(_.publicKey.getKeyID)))
    }
  }

  private def isRevoked(key: PGPPublicKey, master: PGPPublicKey): Boolean = {
    val sigType = if (key.isMasterKey) PGPSignature.KEY_REVOCATION else PGPSignature.SUBKEY_REVOCATION
    key
      .getSignaturesOfType(sigType)
      .asScala
      .collect { case sig: PGPSignature => sig }
      .exists { sig =>
        Try {
          sig.init(new JcaPGPContentVerifierBuilderProvider(), master)
          if (key.isMasterKey) sig.verifyCertification(master) else sig.verifyCertification(master, key)
        }.getOrElse(false)
      }
  }

  private[mill] def fingerprintHex(k: PGPPublicKey): String =
    k.getFingerprint.map(b => f"${b & 0xff}%02X").mkString
}

/**
 * Mill-API-free OpenPGP commit-signature verification: a custom [[org.bouncycastle.openpgp.PGPSignature]] check
 * constructed fresh per call — never the JVM-global JGit [[org.eclipse.jgit.lib.SignatureVerifiers]] registry, so a
 * newly trusted key is honored on the very next call with no daemon restart and no stale negative-key caching.
 */
object SigningVerify {

  /** Verify `commit`'s signature (if any) against `trusted`. */
  def verify(commit: RevCommit, trusted: TrustedKeys): SigningVerdict =
    Option(commit.getRawGpgSignature) match {
      case None           => SigningVerdict.Unsigned
      case Some(sigBytes) =>
        val text = new String(sigBytes, StandardCharsets.US_ASCII)
        if (!text.stripLeading().startsWith("-----BEGIN PGP SIGNATURE-----"))
          SigningVerdict.Unverifiable(armorFormat(text))
        else verifyPgp(commit, sigBytes, trusted)
    }

  private def armorFormat(text: String): String =
    text.linesIterator
      .find(_.startsWith("-----BEGIN"))
      .map(_.stripPrefix("-----BEGIN ").stripSuffix("-----").trim)
      .getOrElse("unknown")

  private def verifyPgp(commit: RevCommit, sigBytes: Array[Byte], trusted: TrustedKeys): SigningVerdict =
    Try {
      val in = PGPUtil.getDecoderStream(new ByteArrayInputStream(sigBytes))
      new JcaPGPObjectFactory(in).nextObject() match {
        case list: PGPSignatureList => (0 until list.size()).map(list.get).toVector
        case other                  =>
          throw new IllegalArgumentException(
            s"expected a PGP signature list, got ${Option(other).map(_.getClass.getSimpleName).getOrElse("nothing")}"
          )
      }
    } match {
      case scala.util.Failure(e)                      => SigningVerdict.Invalid(s"malformed signature: ${e.getMessage}")
      case scala.util.Success(sigs) if sigs.size != 1 => SigningVerdict.Invalid("multi-signature")
      case scala.util.Success(sigs)                   =>
        val sig        = sigs.head
        val payload    = stripGpgSigHeader(commit.getRawBuffer)
        val candidates = trusted.candidates(sig.getKeyID)
        if (candidates.isEmpty) SigningVerdict.Untrusted(f"${sig.getKeyID}%016X")
        else
          candidates.find(c => cryptoVerify(sig, c.publicKey, payload)) match {
            case None                                   => SigningVerdict.Invalid("signature-mismatch")
            case Some(c) if c.revoked                   => SigningVerdict.Revoked(c.fingerprint)
            case Some(c) if isExpired(c.publicKey, sig) => SigningVerdict.Expired(c.fingerprint)
            case Some(c)                                => SigningVerdict.Trusted(c.fingerprint)
          }
    }

  private def cryptoVerify(sig: PGPSignature, key: PGPPublicKey, payload: Array[Byte]): Boolean =
    Try {
      sig.init(new JcaPGPContentVerifierBuilderProvider(), key)
      sig.update(payload)
      sig.verify()
    }.getOrElse(false)

  private def isExpired(key: PGPPublicKey, sig: PGPSignature): Boolean = {
    val sigTime      = sig.getCreationTime.toInstant
    val keyCreated   = key.getCreationTime.toInstant
    val validSeconds = key.getValidSeconds
    sigTime.isBefore(keyCreated) || (validSeconds > 0 && sigTime.isAfter(keyCreated.plusSeconds(validSeconds)))
  }

  /**
   * Reconstruct the bytes that were fed to the signer: the raw commit object with the `gpgsig` header line and its
   * space-indented continuation lines removed. This inverts whatever wrapping `CommitBuilder.build()` applied when the
   * signature was attached, so it holds regardless of the exact header-wrap format used.
   */
  private[mill] def stripGpgSigHeader(raw: Array[Byte]): Array[Byte] = {
    val iso   = StandardCharsets.ISO_8859_1
    val text  = new String(raw, iso)
    val lines = text.split("(?<=\n)").toVector
    val kept  = new StringBuilder
    var inSig = false
    lines.foreach { line =>
      if (!inSig && line.startsWith("gpgsig ")) inSig = true
      else if (inSig && line.startsWith(" ")) ()
      else { inSig = false; kept.append(line) }
    }
    kept.toString.getBytes(iso)
  }
}

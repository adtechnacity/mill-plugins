package atn.mill

import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.openpgp._
import org.bouncycastle.openpgp.jcajce.JcaPGPObjectFactory
import org.bouncycastle.openpgp.operator.jcajce.{JcaKeyFingerprintCalculator, JcaPGPContentVerifierBuilderProvider}

import org.eclipse.jgit.lib.{ObjectId, Repository}
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.PathFilter

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.time.Instant

import scala.jdk.CollectionConverters._
import scala.util.Try

/** The outcome of checking one commit's OpenPGP signature against a [[TrustedKeys]] allowlist. */
sealed trait SigningVerdict {

  /**
   * The rejection line for a commit that triggered `reasons` but did not produce a trusted signature: `None` when the
   * commit passes, otherwise a message naming the short `sha` and the specific remedy. Shared by every enforcement
   * layer (pre-push, CI, pre-receive) so a contributor sees the same wording wherever the rejection surfaces.
   */
  def rejection(sha: String, reasons: Vector[SigningReason]): Option[String] = this match {
    case SigningVerdict.Trusted(_)        => None
    case SigningVerdict.Unsigned          =>
      Some(s"$sha: unsigned but signing is required (${reasons.map(_.condition).distinct.mkString(", ")})")
    case SigningVerdict.Unverifiable(fmt) =>
      Some(s"$sha: signature format '$fmt' is not verifiable (only OpenPGP signatures can be verified)")
    case SigningVerdict.Invalid(reason)   => Some(s"$sha: invalid signature ($reason)")
    case SigningVerdict.Untrusted(fp)     =>
      Some(s"$sha: signed by an untrusted key ($fp) — a maintainer must re-sign or add this key to the trust root")
    case SigningVerdict.Revoked(fp)       => Some(s"$sha: signed by a revoked key ($fp)")
    case SigningVerdict.Expired(fp)       => Some(s"$sha: signed by an expired key ($fp)")
  }
}

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
 * One key loaded from a trust source: the key, its ring's master key (the key itself for a master), its full 40-hex
 * fingerprint, and whether it is revoked — directly, through its subkey binding, or because its master key is.
 */
final private case class LoadedKey(publicKey: PGPPublicKey, master: PGPPublicKey, fingerprint: String, revoked: Boolean)

/**
 * Armored public keys loaded from a worktree directory or a git ref's tree, indexed by OpenPGP key ID for signature
 * lookup. Keys carrying a valid embedded revocation are retained but never treated as trusted, so a signature from one
 * classifies as [[SigningVerdict.Revoked]] rather than [[SigningVerdict.Untrusted]]. Known shared-platform signing keys
 * (e.g. a forge's web-flow key) are refused at load time — trusting them would collapse attribution to "has push
 * access".
 */
final class TrustedKeys private (private val byKeyId: Map[Long, Vector[LoadedKey]]) {
  private[mill] def candidates(keyId: Long): Vector[LoadedKey] = byKeyId.getOrElse(keyId, Vector.empty)
}

object TrustedKeys {

  /**
   * GitHub's web-flow signing keys, current (since January 2024) and the one it replaced — history signed by the old
   * key is still pushed around — identified by their 16-hex long key IDs. Best-effort refusal check only — never used
   * for trust matching, which is always full-fingerprint.
   */
  val KnownPlatformKeyIds: Set[String] = Set("B5690EEEBB952194", "4AEE18F83AFDEB23")

  /** Key files are recognised by extension, so a README or other note can sit beside the keys. */
  private[mill] def isKeyFile(name: String): Boolean = name.endsWith(".asc") || name.endsWith(".gpg")

  /** Whether `dir` holds at least one key file: what activates signing enforcement in the installed hooks. */
  def hasKeyFiles(dir: os.Path): Boolean = os.isDir(dir) && os.list(dir).exists(f => os.isFile(f) && isKeyFile(f.last))

  /** Load every `*.asc`/`*.gpg` file in `dir` as an armored key source. */
  def fromDirectory(dir: os.Path): Either[String, TrustedKeys] =
    if (!os.exists(dir) || !os.isDir(dir)) Left(s"trusted-keys configuration error: directory not found: $dir")
    else {
      val files = os.list(dir).filter(f => os.isFile(f) && isKeyFile(f.last))
      if (files.isEmpty) Left(s"trusted-keys configuration error: no key files (*.asc, *.gpg) found in $dir")
      else parseAndBuild(files.map(f => f.last -> os.read.bytes(f)))
    }

  /**
   * Load every `*.asc`/`*.gpg` blob under `path` in `refName`'s tree as an armored key source — never the working tree
   * or an untrusted ref.
   */
  def fromRef(repo: Repository, refName: String, path: String): Either[String, TrustedKeys] =
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
              .filter(_ => isKeyFile(walk.getNameString))
              .map(_ => walk.getPathString -> reader.open(walk.getObjectId(0)).getBytes)
              .toVector
            if (files.isEmpty) Left(s"trusted-keys configuration error: no key files found at $refName:$path")
            else parseAndBuild(files)
          } finally reader.close()
        } finally walk.close()
    }

  private def parseAndBuild(sources: Seq[(String, Array[Byte])]): Either[String, TrustedKeys] = {
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
      .flatMap(build(_))
  }

  /**
   * `knownPlatformKeyIds` is a test seam — production callers always get [[KnownPlatformKeyIds]] via
   * [[fromDirectory]]/[[fromRef]].
   */
  private[mill] def build(
    rings: Vector[PGPPublicKeyRing],
    knownPlatformKeyIds: Set[String] = KnownPlatformKeyIds
  ): Either[String, TrustedKeys] = {
    val allKeys = rings.flatMap(_.getPublicKeys.asScala.toVector)
    val refused = allKeys.filter(k => knownPlatformKeyIds.contains(f"${k.getKeyID}%016X"))
    if (refused.nonEmpty)
      Left(
        "refusing to load known shared platform signing key(s): " +
          refused.map(k => f"${k.getKeyID}%016X").mkString(", ") +
          " — trusting these collapses attribution to \"has push access\""
      )
    else {
      val loaded = rings.flatMap { ring =>
        val master        = ring.getPublicKey
        val masterRevoked = isRevoked(master, master)
        ring.getPublicKeys.asScala.toVector.map { key =>
          LoadedKey(key, master, fingerprintHex(key), revoked = masterRevoked || isRevoked(key, master))
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

  /** Digests a signature may be made over; SHA-1 and older are collision-prone and refused. */
  private val StrongHashes: Set[Int] = Set(
    HashAlgorithmTags.SHA224,
    HashAlgorithmTags.SHA256,
    HashAlgorithmTags.SHA384,
    HashAlgorithmTags.SHA512,
    HashAlgorithmTags.SHA3_256,
    HashAlgorithmTags.SHA3_512
  )

  /**
   * Verify `commit`'s signature (if any) against `trusted`. The signing key must be valid both when the signature says
   * it was made and at `now`: the signer picks the signature's timestamp, so the signing-time check alone would let an
   * expired key backdate its way back into its validity window.
   */
  def verify(commit: RevCommit, trusted: TrustedKeys, now: Instant = Instant.now()): SigningVerdict =
    Option(commit.getRawGpgSignature) match {
      case None           => SigningVerdict.Unsigned
      case Some(sigBytes) =>
        val text = new String(sigBytes, StandardCharsets.US_ASCII)
        if (!text.stripLeading().startsWith("-----BEGIN PGP SIGNATURE-----"))
          SigningVerdict.Unverifiable(armorFormat(text))
        else verifyPgp(commit, sigBytes, trusted, now)
    }

  private def armorFormat(text: String): String =
    text.linesIterator
      .find(_.startsWith("-----BEGIN"))
      .map(_.stripPrefix("-----BEGIN ").stripSuffix("-----").trim)
      .getOrElse("unknown")

  private def verifyPgp(commit: RevCommit, sigBytes: Array[Byte], trusted: TrustedKeys, now: Instant): SigningVerdict =
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
      case scala.util.Failure(e)                                                  => SigningVerdict.Invalid(s"malformed signature: ${e.getMessage}")
      case scala.util.Success(sigs) if sigs.size != 1                             => SigningVerdict.Invalid("multi-signature")
      case scala.util.Success(Vector(sig)) if !StrongHashes(sig.getHashAlgorithm) =>
        SigningVerdict.Invalid(s"weak hash algorithm ${digestName(sig.getHashAlgorithm)}")
      case scala.util.Success(sigs)                                               =>
        val sig        = sigs.head
        val payload    = stripGpgSigHeader(commit.getRawBuffer)
        val candidates = trusted.candidates(sig.getKeyID)
        if (candidates.isEmpty) SigningVerdict.Untrusted(f"${sig.getKeyID}%016X")
        else
          candidates.find(c => cryptoVerify(sig, c.publicKey, payload)) match {
            case None                              => SigningVerdict.Invalid("signature-mismatch")
            case Some(c) if c.revoked              => SigningVerdict.Revoked(c.fingerprint)
            case Some(c) if isExpired(c, sig, now) => SigningVerdict.Expired(c.fingerprint)
            case Some(c)                           => SigningVerdict.Trusted(c.fingerprint)
          }
    }

  private def cryptoVerify(sig: PGPSignature, key: PGPPublicKey, payload: Array[Byte]): Boolean =
    Try {
      sig.init(new JcaPGPContentVerifierBuilderProvider(), key)
      sig.update(payload)
      sig.verify()
    }.getOrElse(false)

  private def digestName(algorithm: Int): String = Try(PGPUtil.getDigestName(algorithm)).getOrElse(s"#$algorithm")

  /** A subkey is only as valid as its master key, so both validity windows apply. */
  private def isExpired(key: LoadedKey, sig: PGPSignature, now: Instant): Boolean = {
    val sigTime = sig.getCreationTime.toInstant
    Seq(key.publicKey, key.master).distinct.exists { k =>
      val created      = k.getCreationTime.toInstant
      val validSeconds = k.getValidSeconds
      val expired      = (t: Instant) => validSeconds > 0 && t.isAfter(created.plusSeconds(validSeconds))
      sigTime.isBefore(created) || expired(sigTime) || expired(now)
    }
  }

  /**
   * Reconstruct the bytes that were fed to the signer: the raw commit object with the `gpgsig` header line and its
   * space-indented continuation lines removed. This inverts whatever wrapping `CommitBuilder.build()` applied when the
   * signature was attached, so it holds regardless of the exact header-wrap format used. Only the header is searched:
   * after the blank line that ends it, a message line reading `gpgsig ...` is signed content like any other.
   */
  private[mill] def stripGpgSigHeader(raw: Array[Byte]): Array[Byte] = {
    val iso       = StandardCharsets.ISO_8859_1
    val text      = new String(raw, iso)
    val headerEnd = Option(text.indexOf("\n\n")).filter(_ >= 0).map(_ + 1).getOrElse(text.length)
    // Split keeps each line's trailing newline, so the kept lines reconstruct the original bytes exactly.
    val lines     = text.take(headerEnd).split("(?<=\n)").toVector
    val (kept, _) = lines.foldLeft((Vector.empty[String], false)) { case ((kept, inSig), line) =>
      if ((!inSig && line.startsWith("gpgsig ")) || (inSig && line.startsWith(" "))) (kept, true)
      else (kept :+ line, false)
    }
    (kept.mkString + text.drop(headerEnd)).getBytes(iso)
  }
}

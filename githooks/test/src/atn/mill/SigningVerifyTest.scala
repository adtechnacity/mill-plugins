package atn.mill

import utest._

import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.openpgp.{PGPKeyPair, PGPPublicKeyRing}

import org.eclipse.jgit.revwalk.RevWalk

import java.util.Date

import scala.concurrent.duration._

object SigningVerifyTest extends TestSuite {

  import GitFixtures._

  def commitOf(git: org.eclipse.jgit.api.Git, id: org.eclipse.jgit.lib.ObjectId) = {
    val walk = new RevWalk(git.getRepository)
    try walk.parseCommit(id)
    finally walk.close()
  }

  def withKeysDir[A](armored: String*)(body: os.Path => A): A = {
    val dir = os.temp.dir(prefix = "trusted-keys")
    armored.zipWithIndex.foreach { case (a, i) => os.write(dir / s"key-$i.asc", a) }
    body(dir)
  }

  def fingerprint(key: PGPKeyPair): String = TrustedKeys.fingerprintHex(key.getPublicKey)

  def daysAgo(n: Int): Date = new Date(System.currentTimeMillis() - n.days.toMillis)

  /** The verdict on a one-file commit `signer` signed, checked against a trust store holding only `ring`. */
  def verdictOf(
    ring: PGPPublicKeyRing,
    signer: PGPKeyPair,
    signatureTime: Date = new Date(),
    hashAlgorithm: Int = HashAlgorithmTags.SHA256,
    msg: String = "signed"
  ): SigningVerdict =
    withRepo { (_, git) =>
      withKeysDir(armorRing(ring)) { dir =>
        val Right(trusted) = TrustedKeys.fromDirectory(dir): @unchecked
        val signed         = signedCommit(git, Map("a.txt" -> "x"), Nil, msg, signer, signatureTime, hashAlgorithm)
        SigningVerify.verify(commitOf(git, signed), trusted)
      }
    }

  val tests = Tests {

    test("trusted-signed commit by a master key verifies as Trusted with its fingerprint") {
      val signer = genKeyPair()
      verdictOf(keyRing(signer), signer) ==> SigningVerdict.Trusted(fingerprint(signer))
    }

    test("subkey-signed commit verifies as Trusted when the full ring (master + subkey) is present") {
      val sub = genKeyPair()
      verdictOf(keyRing(genKeyPair(), subkey = Some(sub)), sub) ==> SigningVerdict.Trusted(fingerprint(sub))
    }

    test("unsigned commit is Unsigned") {
      withRepo { (dir, git) =>
        val trusted = withKeysDir(armorRing(keyRing(genKeyPair())))(TrustedKeys.fromDirectory(_)).toOption.get
        val commit  = commitFile(git, dir, "a.txt", "x", "plain")
        SigningVerify.verify(commit, trusted) ==> SigningVerdict.Unsigned
      }
    }

    test("commit signed by a key absent from the trust store is Untrusted, named by key id") {
      withRepo { (_, git) =>
        val signer  = genKeyPair()
        val other   = genKeyPair()
        val trusted = withKeysDir(armorRing(keyRing(other)))(TrustedKeys.fromDirectory(_)).toOption.get
        val commit  = commitOf(git, signedCommit(git, Map("a.txt" -> "x"), Nil, "signed", signer))
        SigningVerify.verify(commit, trusted) ==> SigningVerdict.Untrusted(f"${signer.getPublicKey.getKeyID}%016X")
      }
    }

    test("a revoked subkey in the ring verifies as Revoked, never Trusted") {
      val master = genKeyPair()
      val sub    = genKeyPair()
      val ring   = revoke(keyRing(master, subkey = Some(sub)), master, subkeyId = Some(sub.getKeyID))
      verdictOf(ring, sub) ==> SigningVerdict.Revoked(fingerprint(sub))
    }

    test("a signature made after the key's validity window is Expired") {
      val signer = genKeyPair(daysAgo(3))
      verdictOf(keyRing(signer, expireAfter = Some(1.days.toSeconds)), signer) ==> SigningVerdict.Expired(
        fingerprint(signer)
      )
    }

    test("a signature blob carrying two signature packets is Invalid as multi-signature") {
      withRepo { (_, git) =>
        val signer1 = genKeyPair()
        val signer2 = genKeyPair()
        val trusted = withKeysDir(armorRing(keyRing(signer1)))(TrustedKeys.fromDirectory(_)).toOption.get
        val commit  = commitOf(git, multiSignedCommit(git, Map("a.txt" -> "x"), Nil, "signed", signer1, signer2))
        SigningVerify.verify(commit, trusted) ==> SigningVerdict.Invalid("multi-signature")
      }
    }

    test("a known shared platform key is refused at load, naming the offending key id") {
      val platform = genKeyPair()
      val hex      = f"${platform.getPublicKey.getKeyID}%016X"

      val refused = TrustedKeys.build(Vector(keyRing(platform)), knownPlatformKeyIds = Set(hex))
      assert(refused.isLeft)
      assert(refused.left.exists(_.contains(hex)))

      // A key outside the platform set loads normally — the refusal is targeted, not a blanket rejection.
      assert(TrustedKeys.build(Vector(keyRing(genKeyPair())), knownPlatformKeyIds = Set(hex)).isRight)
    }

    test("keys loaded from a git ref's tree verify identically to keys loaded from a worktree directory") {
      withRepo { (dir, git) =>
        val signer  = genKeyPair()
        val armored = armorRing(keyRing(signer))
        commitFile(git, dir, "keys/signer.asc", armored, "add key")

        val Right(fromTree)      = TrustedKeys.fromRef(git.getRepository, "refs/heads/main", "keys"): @unchecked
        val fromDirEither        = withKeysDir(armored)(TrustedKeys.fromDirectory(_))
        val Right(fromDirectory) = fromDirEither: @unchecked

        val commit = commitOf(git, signedCommit(git, Map("a.txt" -> "x"), Nil, "signed", signer))
        SigningVerify.verify(commit, fromTree) ==> SigningVerify.verify(commit, fromDirectory)
      }
    }

    test("a corrupt armored key file fails clearly, naming the file") {
      withKeysDir("not a pgp key") { dir =>
        os.remove(dir / "key-0.asc")
        os.write(dir / "bogus.asc", "-----BEGIN PGP PUBLIC KEY BLOCK-----\nnot valid\n")
        val result = TrustedKeys.fromDirectory(dir)
        assert(result.isLeft)
        assert(result.left.exists(_.contains("bogus.asc")))
      }
    }

    test("an empty keys directory is a configuration error, not a silent empty-but-passing trust store") {
      val dir    = os.temp.dir(prefix = "empty-keys")
      val result = TrustedKeys.fromDirectory(dir)
      assert(result.isLeft)
    }

    test("a key added after one load is honored by a fresh load, with no registry or cache") {
      withRepo { (_, git) =>
        val signer        = genKeyPair()
        val commit        = commitOf(git, signedCommit(git, Map("a.txt" -> "x"), Nil, "signed", signer))
        val dir           = os.temp.dir(prefix = "growing-keys")
        os.write(dir / "placeholder.asc", armorRing(keyRing(genKeyPair())))
        val Right(before) = TrustedKeys.fromDirectory(dir): @unchecked
        SigningVerify.verify(commit, before) ==> SigningVerdict.Untrusted(f"${signer.getPublicKey.getKeyID}%016X")

        os.write(dir / "signer.asc", armorRing(keyRing(signer)))
        val Right(after) = TrustedKeys.fromDirectory(dir): @unchecked
        SigningVerify.verify(commit, after) ==> SigningVerdict.Trusted(TrustedKeys.fingerprintHex(signer.getPublicKey))
      }
    }

    test("a subkey of a revoked master key verifies as Revoked, never Trusted") {
      val master = genKeyPair()
      val sub    = genKeyPair()
      verdictOf(revoke(keyRing(master, subkey = Some(sub)), master), sub) ==> SigningVerdict.Revoked(fingerprint(sub))
    }

    test("a subkey signature made after its master key expired is Expired") {
      val sub  = genKeyPair(daysAgo(3))
      val ring = keyRing(genKeyPair(daysAgo(3)), subkey = Some(sub), masterExpireAfter = Some(1.days.toSeconds))
      verdictOf(ring, sub) ==> SigningVerdict.Expired(fingerprint(sub))
    }

    test("a signature backdated into the validity window of a key that has since expired is Expired") {
      val signer    = genKeyPair(daysAgo(3))
      val backdated = new Date(System.currentTimeMillis() - 60.hours.toMillis)
      val ring      = keyRing(signer, expireAfter = Some(1.days.toSeconds))
      verdictOf(ring, signer, signatureTime = backdated) ==> SigningVerdict.Expired(fingerprint(signer))
    }

    test("a signature over a SHA-1 digest is Invalid as a weak hash, even from a trusted key") {
      val signer = genKeyPair()
      verdictOf(keyRing(signer), signer, hashAlgorithm = HashAlgorithmTags.SHA1) ==>
        SigningVerdict.Invalid("weak hash algorithm SHA1")
    }

    test("a message line that looks like a gpgsig header is signed content, not stripped") {
      val signer = genKeyPair()
      val msg    = "subject\n\ngpgsig is mentioned here\n indented continuation\n"
      verdictOf(keyRing(signer), signer, msg = msg) ==> SigningVerdict.Trusted(fingerprint(signer))
    }

    test("non-key files beside the keys are ignored, and a directory of only non-key files holds no keys") {
      withKeysDir(armorRing(keyRing(genKeyPair()))) { dir =>
        os.write(dir / "README.md", "Trusted signers for this repo.\n")
        assert(TrustedKeys.fromDirectory(dir).isRight)
        assert(TrustedKeys.hasKeyFiles(dir))
      }
      val onlyReadme = os.temp.dir(prefix = "readme-only")
      os.write(onlyReadme / "README.md", "no keys yet\n")
      assert(TrustedKeys.fromDirectory(onlyReadme).left.exists(_.contains("no key files")))
      assert(!TrustedKeys.hasKeyFiles(onlyReadme))
    }

    test("both of GitHub's web-flow signing keys, current and pre-2024, are known platform keys") {
      assert(TrustedKeys.KnownPlatformKeyIds == Set("B5690EEEBB952194", "4AEE18F83AFDEB23"))
    }
  }
}

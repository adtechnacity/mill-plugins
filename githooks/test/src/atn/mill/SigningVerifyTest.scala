package atn.mill

import utest._

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

  val tests = Tests {

    test("trusted-signed commit by a master key verifies as Trusted with its fingerprint") {
      withRepo { (_, git) =>
        val signer = genKeyPair()
        val ring   = keyRing(signer)
        withKeysDir(armorRing(ring)) { dir =>
          val Right(trusted) = TrustedKeys.fromDirectory(dir): @unchecked
          val commit         = commitOf(git, signedCommit(git, Map("a.txt" -> "x"), Nil, "signed", signer))
          SigningVerify.verify(commit, trusted) ==> SigningVerdict.Trusted(
            TrustedKeys.fingerprintHex(signer.getPublicKey)
          )
        }
      }
    }

    test("subkey-signed commit verifies as Trusted when the full ring (master + subkey) is present") {
      withRepo { (_, git) =>
        val master = genKeyPair()
        val sub    = genKeyPair()
        val ring   = keyRing(master, subkey = Some(sub))
        withKeysDir(armorRing(ring)) { dir =>
          val Right(trusted) = TrustedKeys.fromDirectory(dir): @unchecked
          val commit         = commitOf(git, signedCommit(git, Map("a.txt" -> "x"), Nil, "signed", sub))
          SigningVerify.verify(commit, trusted) ==> SigningVerdict.Trusted(TrustedKeys.fingerprintHex(sub.getPublicKey))
        }
      }
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
      withRepo { (_, git) =>
        val master  = genKeyPair()
        val sub     = genKeyPair()
        val bound   = keyRing(master, subkey = Some(sub))
        val revoked = revoke(bound, master, subkeyId = Some(sub.getKeyID))
        withKeysDir(armorRing(revoked)) { dir =>
          val Right(trusted) = TrustedKeys.fromDirectory(dir): @unchecked
          val commit         = commitOf(git, signedCommit(git, Map("a.txt" -> "x"), Nil, "signed", sub))
          SigningVerify.verify(commit, trusted) ==> SigningVerdict.Revoked(TrustedKeys.fingerprintHex(sub.getPublicKey))
        }
      }
    }

    test("a signature made after the key's validity window is Expired") {
      withRepo { (_, git) =>
        val created = new Date(System.currentTimeMillis() - 3.days.toMillis)
        val signer  = genKeyPair(created)
        val ring    = keyRing(signer, expireAfter = Some(1.days.toSeconds))
        withKeysDir(armorRing(ring)) { dir =>
          val Right(trusted) = TrustedKeys.fromDirectory(dir): @unchecked
          val commit         =
            commitOf(git, signedCommit(git, Map("a.txt" -> "x"), Nil, "signed", signer, signatureTime = new Date()))
          SigningVerify.verify(commit, trusted) ==> SigningVerdict.Expired(
            TrustedKeys.fingerprintHex(signer.getPublicKey)
          )
        }
      }
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
  }
}

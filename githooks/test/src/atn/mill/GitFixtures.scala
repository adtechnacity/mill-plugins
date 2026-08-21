package atn.mill

import org.bouncycastle.bcpg.{ArmoredOutputStream, HashAlgorithmTags, PublicKeyAlgorithmTags}
import org.bouncycastle.openpgp._
import org.bouncycastle.openpgp.operator.jcajce.{
  JcaPGPContentSignerBuilder,
  JcaPGPDigestCalculatorProviderBuilder,
  JcaPGPKeyPair
}

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.{
  CommitBuilder,
  Config,
  Constants,
  FileMode,
  GpgSignature,
  ObjectId,
  PersonIdent,
  TreeFormatter
}
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.util.{FS, SystemReader}

import java.io.ByteArrayOutputStream
import java.security.{KeyPairGenerator, SecureRandom, Security}
import java.util.Date

/**
 * Shared JGit fixture-repo helpers. Every fixture runs hermetic: a [[SystemReader]] delegate hides the host's
 * user/system git config, so machine-level settings (a global `commit.gpgsign`, signing keys, hooks) can never leak
 * into test behavior.
 */
object GitFixtures {

  Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider())

  val ident = new PersonIdent("Test Fixture", "fixture@example.com")

  private def emptyConfig(dir: os.Path, name: String) =
    new FileBasedConfig(null, (dir / name).toIO, FS.DETECTED)

  /** Run `body` with user/system/jgit git config replaced by empty configs. */
  def hermetic[A](body: => A): A = {
    val prev = SystemReader.getInstance()
    val tmp  = os.temp.dir(prefix = "git-fixture-cfg")
    SystemReader.setInstance(new SystemReader.Delegate(prev) {
      override def openUserConfig(parent: Config, fs: FS): FileBasedConfig   = emptyConfig(tmp, "user.gitconfig")
      override def openSystemConfig(parent: Config, fs: FS): FileBasedConfig = emptyConfig(tmp, "system.gitconfig")
      override def openJGitConfig(parent: Config, fs: FS): FileBasedConfig   = emptyConfig(tmp, "jgit.gitconfig")
    })
    try body
    finally SystemReader.setInstance(prev)
  }

  /** A fresh hermetic repo on branch `main` in a temp dir. */
  def withRepo[A](body: (os.Path, Git) => A): A = hermetic {
    val dir = os.temp.dir(prefix = "git-fixture")
    val git = Git.init().setDirectory(dir.toIO).setInitialBranch("main").call()
    try body(dir, git)
    finally git.close()
  }

  def commit(git: Git, msg: String): RevCommit =
    git.commit().setMessage(msg).setSign(false).setAuthor(ident).setCommitter(ident).call()

  def commitFile(git: Git, dir: os.Path, path: String, content: String, msg: String): RevCommit = {
    os.write.over(dir / os.SubPath(path), content, createFolders = true)
    git.add().addFilepattern(path).call()
    commit(git, msg)
  }

  /**
   * Build a commit whose tree is exactly `files` (flat names only), with arbitrary parents — the way to construct
   * precise merge shapes without running a merge. Returns the commit id; no ref is updated.
   */
  def rawCommit(git: Git, files: Map[String, String], parents: Seq[ObjectId], msg: String): ObjectId = {
    val repo = git.getRepository
    val ins  = repo.newObjectInserter()
    try {
      val tf  = new TreeFormatter()
      files.toSeq.sortBy(_._1).foreach { case (name, content) =>
        val blob = ins.insert(Constants.OBJ_BLOB, content.getBytes("UTF-8"))
        tf.append(name, FileMode.REGULAR_FILE, blob)
      }
      val cb  = new CommitBuilder()
      cb.setTreeId(ins.insert(tf))
      cb.setParentIds(parents*)
      cb.setAuthor(ident)
      cb.setCommitter(ident)
      cb.setMessage(msg)
      val cid = ins.insert(cb)
      ins.flush()
      cid
    } finally ins.close()
  }

  /** An in-memory RSA-2048 OpenPGP key pair; never touches a real GPG home or a keyserver. */
  def genKeyPair(created: Date = new Date()): PGPKeyPair = {
    val kpg = KeyPairGenerator.getInstance("RSA", "BC")
    kpg.initialize(2048, new SecureRandom())
    new JcaPGPKeyPair(PublicKeyAlgorithmTags.RSA_GENERAL, kpg.generateKeyPair(), created)
  }

  /**
   * A public key ring for `master`, self-certified for `userId`, optionally carrying one signing subkey. `expireAfter`
   * (seconds; `None` = never expires) applies to whichever key (`master`, or the subkey when present) will do the
   * signing, matching real `gpg --quick-generate-key --expire-date` semantics closely enough for verification tests.
   */
  def keyRing(
    master: PGPKeyPair,
    userId: String = "Test Signer <signer@example.com>",
    subkey: Option[PGPKeyPair] = None,
    expireAfter: Option[Long] = None
  ): PGPPublicKeyRing = {
    val sha1Calc   = new JcaPGPDigestCalculatorProviderBuilder().build().get(HashAlgorithmTags.SHA1)
    val masterSubs = new PGPSignatureSubpacketGenerator()
    if (subkey.isEmpty) expireAfter.foreach(s => masterSubs.setKeyExpirationTime(false, s))
    val sigBuilder = new JcaPGPContentSignerBuilder(master.getPublicKey.getAlgorithm, HashAlgorithmTags.SHA256)
    val gen        = new PGPKeyRingGenerator(
      PGPSignature.POSITIVE_CERTIFICATION,
      master,
      userId,
      sha1Calc,
      masterSubs.generate(),
      null,
      sigBuilder,
      null
    )
    subkey.foreach { sk =>
      val subSubs = new PGPSignatureSubpacketGenerator()
      expireAfter.foreach(s => subSubs.setKeyExpirationTime(false, s))
      gen.addSubKey(sk, subSubs.generate(), null)
    }
    gen.generatePublicKeyRing()
  }

  /** Attach a self-signed revocation to `ring`'s master key (or, when `subkeyId` is given, to that subkey). */
  def revoke(ring: PGPPublicKeyRing, master: PGPKeyPair, subkeyId: Option[Long] = None): PGPPublicKeyRing = {
    val target     = subkeyId.map(id => ring.getPublicKey(id)).getOrElse(ring.getPublicKey)
    val sigType    = if (subkeyId.isEmpty) PGPSignature.KEY_REVOCATION else PGPSignature.SUBKEY_REVOCATION
    val sigBuilder = new JcaPGPContentSignerBuilder(master.getPublicKey.getAlgorithm, HashAlgorithmTags.SHA256)
    val sigGen     = new PGPSignatureGenerator(sigBuilder)
    sigGen.init(sigType, master.getPrivateKey)
    val cert       =
      if (subkeyId.isEmpty) sigGen.generateCertification(target)
      else sigGen.generateCertification(master.getPublicKey, target)
    PGPPublicKeyRing.insertPublicKey(ring, PGPPublicKey.addCertification(target, cert))
  }

  /** Armor-export a public key ring, suitable for writing to a [[TrustedKeys]] fixture directory. */
  def armorRing(ring: PGPPublicKeyRing): String = {
    val out   = new ByteArrayOutputStream()
    val armor = new ArmoredOutputStream(out)
    ring.encode(armor)
    armor.close()
    out.toString("US-ASCII")
  }

  /** Build a not-yet-inserted commit + its pre-signature payload bytes (what a real signer signs). */
  private def unsignedCommit(
    repo: org.eclipse.jgit.lib.Repository,
    ins: org.eclipse.jgit.lib.ObjectInserter,
    files: Map[String, String],
    parents: Seq[ObjectId],
    msg: String
  ): (CommitBuilder, Array[Byte]) = {
    val tf = new TreeFormatter()
    files.toSeq.sortBy(_._1).foreach { case (name, content) =>
      val blob = ins.insert(Constants.OBJ_BLOB, content.getBytes("UTF-8"))
      tf.append(name, FileMode.REGULAR_FILE, blob)
    }
    val cb = new CommitBuilder()
    cb.setTreeId(ins.insert(tf))
    cb.setParentIds(parents*)
    cb.setAuthor(ident)
    cb.setCommitter(ident)
    cb.setMessage(msg)
    (cb, cb.build())
  }

  private def detachedSignature(payload: Array[Byte], signer: PGPKeyPair, signatureTime: Date): PGPSignature = {
    val sigGen = new PGPSignatureGenerator(
      new JcaPGPContentSignerBuilder(signer.getPublicKey.getAlgorithm, HashAlgorithmTags.SHA256)
    )
    sigGen.init(PGPSignature.BINARY_DOCUMENT, signer.getPrivateKey)
    val subs   = new PGPSignatureSubpacketGenerator()
    subs.setSignatureCreationTime(false, signatureTime)
    sigGen.setHashedSubpackets(subs.generate())
    sigGen.update(payload)
    sigGen.generate()
  }

  private def armor(sigs: PGPSignature*): Array[Byte] = {
    val out   = new ByteArrayOutputStream()
    val armor = new ArmoredOutputStream(out)
    sigs.foreach(_.encode(armor))
    armor.close()
    out.toByteArray
  }

  /**
   * Build a commit exactly like [[rawCommit]], then attach a detached OpenPGP signature over its header-stripped raw
   * bytes signed by `signer`, so the result round-trips through `RevCommit.getRawGpgSignature()` like a real
   * `git commit -S`. `signatureTime` lets tests place the signature outside a short-lived key's validity window.
   */
  def signedCommit(
    git: Git,
    files: Map[String, String],
    parents: Seq[ObjectId],
    msg: String,
    signer: PGPKeyPair,
    signatureTime: Date = new Date()
  ): ObjectId = {
    val ins = git.getRepository.newObjectInserter()
    try {
      val (cb, payload) = unsignedCommit(git.getRepository, ins, files, parents, msg)
      cb.setGpgSignature(new GpgSignature(armor(detachedSignature(payload, signer, signatureTime))))
      val cid           = ins.insert(cb)
      ins.flush()
      cid
    } finally ins.close()
  }

  /** A commit whose `gpgsig` header carries two independent signature packets in one armored blob. */
  def multiSignedCommit(
    git: Git,
    files: Map[String, String],
    parents: Seq[ObjectId],
    msg: String,
    signer1: PGPKeyPair,
    signer2: PGPKeyPair
  ): ObjectId = {
    val ins = git.getRepository.newObjectInserter()
    try {
      val (cb, payload) = unsignedCommit(git.getRepository, ins, files, parents, msg)
      val sig1          = detachedSignature(payload, signer1, new Date())
      val sig2          = detachedSignature(payload, signer2, new Date())
      cb.setGpgSignature(new GpgSignature(armor(sig1, sig2)))
      val cid           = ins.insert(cb)
      ins.flush()
      cid
    } finally ins.close()
  }
}

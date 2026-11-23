package atn.mill

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

import mill.api.daemon.Result

/**
 * Pure-HTTP CodeScene code coverage uploader (no `cs-coverage` CLI dependency).
 *
 * Implements CodeScene's two-step coverage upload protocol:
 *   1. `POST <apiBaseUrl>/v2/code-coverage/upload` with JSON metadata (`commit-sha`, `repo-url`, `repo-path`,
 *      `subpath`, `format`, `metric`) and `Authorization: Bearer <token>`, returning `{"ref": "<upload-path>"}`.
 *   2. `PUT <apiBaseUrl><ref>` with the gzip-compressed coverage report (`Content-Type: application/octet-stream`, max
 *      8 MiB compressed).
 *
 * For CodeScene Cloud `apiBaseUrl` is `https://api.codescene.io`; for On-Prem it is `<host>/api` (or `<host>` with
 * `/api` included).
 */
object CodeSceneCoverageUploader:

  /** Maximum compressed payload size accepted by CodeScene (8 MiB). */
  val MaxCompressedBytes: Int = 8 * 1024 * 1024

  /** Parameters for a single coverage upload. */
  final case class UploadRequest(
    apiBaseUrl: String,
    token: String,
    commitSha: String,
    repoUrl: String,
    repoPath: String,
    subpath: String,
    format: String,
    metric: String,
    reportFile: os.Path
  )

  /** Compresses `bytes` using gzip. */
  def gzip(bytes: Array[Byte]): Array[Byte] =
    val out = new ByteArrayOutputStream()
    val gz  = new GZIPOutputStream(out)
    try gz.write(bytes)
    finally gz.close()
    out.toByteArray

  /**
   * Uploads `req.reportFile` to CodeScene via HTTP POST + PUT. Returns the server's upload `ref` path on success, or a
   * descriptive [[Result.Failure]] when the file is missing, too large, or either HTTP step fails.
   */
  def upload(req: UploadRequest): Result[String] =
    if !os.exists(req.reportFile) then Result.Failure(s"Coverage report not found: ${req.reportFile}")
    else
      val compressed = gzip(os.read.bytes(req.reportFile))
      if compressed.length > MaxCompressedBytes then
        Result.Failure(s"Compressed coverage report (${compressed.length} bytes) exceeds CodeScene's 8 MiB limit ($MaxCompressedBytes bytes)")
      else
        val base        = req.apiBaseUrl.stripSuffix("/")
        val authHeaders = Map("Authorization" -> s"Bearer ${req.token}")
        val metadata    = ujson.Obj(
          "commit-sha" -> req.commitSha,
          "repo-url"   -> req.repoUrl,
          "repo-path"  -> req.repoPath,
          "subpath"    -> req.subpath,
          "format"     -> req.format,
          "metric"     -> req.metric
        )
        requestUploadRef(s"$base/v2/code-coverage/upload", authHeaders, metadata).flatMap { ref =>
          val uploadUrl = if ref.startsWith("http://") || ref.startsWith("https://") then ref else s"$base$ref"
          putCompressed(uploadUrl, authHeaders, compressed).map(_ => ref)
        }

  private def requestUploadRef(url: String, authHeaders: Map[String, String], metadata: ujson.Obj): Result[String] =
    val resp = requests.post(
      url = url,
      headers = authHeaders + ("Content-Type" -> "application/json") + ("Accept" -> "application/json"),
      data = ujson.write(metadata),
      check = false
    )
    if resp.statusCode < 200 || resp.statusCode >= 300 then
      Result.Failure(s"CodeScene coverage upload init failed (HTTP ${resp.statusCode}): ${resp.text()}")
    else
      val parsed = Result.create(ujson.read(resp.text()))
      parsed.flatMap { json =>
        json.obj.get("ref").flatMap(_.strOpt).filter(_.nonEmpty) match
          case Some(ref) => Result.Success(ref)
          case None      => Result.Failure(s"CodeScene coverage upload response missing 'ref': ${resp.text()}")
      }

  private def putCompressed(url: String, authHeaders: Map[String, String], compressed: Array[Byte]): Result[Unit] =
    val resp = requests.put(
      url = url,
      headers = authHeaders + ("Content-Type" -> "application/octet-stream"),
      data = compressed,
      check = false
    )
    if resp.statusCode < 200 || resp.statusCode >= 300 then
      Result.Failure(s"CodeScene coverage data PUT failed (HTTP ${resp.statusCode}): ${resp.text()}")
    else Result.Success(())

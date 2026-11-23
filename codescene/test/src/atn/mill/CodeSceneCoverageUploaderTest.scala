package atn.mill

import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.zip.GZIPInputStream

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import mill.api.daemon.Result
import utest.*

import scala.jdk.CollectionConverters.*

object CodeSceneCoverageUploaderTest extends TestSuite:

  final case class CapturedRequest(method: String, path: String, headers: Map[String, String], rawBody: Array[Byte]):
    def bodyText: String = new String(rawBody, StandardCharsets.UTF_8)

  private[mill] def withFakeServer[A](routes: PartialFunction[CapturedRequest, (Int, String)])(
    body: (String, () => List[CapturedRequest]) => A
  ): A =
    val server   = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    val captured = new ConcurrentLinkedQueue[CapturedRequest]
    server.createContext(
      "/",
      (ex: HttpExchange) =>
        val headers         = ex.getRequestHeaders.asScala.map((k, v) => k.toLowerCase -> v.asScala.mkString(",")).toMap
        val bytes           = ex.getRequestBody.readAllBytes()
        val req             = CapturedRequest(ex.getRequestMethod, ex.getRequestURI.getPath, headers, bytes)
        captured.add(req)
        val (status, reply) = routes.applyOrElse(req, (_: CapturedRequest) => (404, """{"error":"not found"}"""))
        val out             = reply.getBytes(StandardCharsets.UTF_8)
        ex.sendResponseHeaders(status, if out.isEmpty then -1L else out.length.toLong)
        if out.nonEmpty then ex.getResponseBody.write(out)
        ex.close()
    )
    server.start()
    try body(s"http://127.0.0.1:${server.getAddress.getPort}", () => captured.asScala.toList)
    finally server.stop(0)

  private def gunzip(bytes: Array[Byte]): String =
    val in = new GZIPInputStream(new ByteArrayInputStream(bytes))
    try new String(in.readAllBytes(), StandardCharsets.UTF_8)
    finally in.close()

  val tests = Tests:

    test("upload - posts metadata to /v2/code-coverage/upload then puts gzipped report to returned ref"):
      val reportFile = os.temp("<coverage><packages/></coverage>", prefix = "cobertura-", suffix = ".xml")
      withFakeServer {
        case r if r.method == "POST" && r.path == "/v2/code-coverage/upload"        =>
          (200, """{"ref":"/v2/code-coverage/upload/abc-123"}""")
        case r if r.method == "PUT" && r.path == "/v2/code-coverage/upload/abc-123" =>
          (200, """{"ok":true}""")
      } { (baseUrl, requests) =>
        val res  = CodeSceneCoverageUploader.upload(
          CodeSceneCoverageUploader.UploadRequest(
            apiBaseUrl = baseUrl,
            token = "secret-token",
            commitSha = "0123456789abcdef0123456789abcdef01234567",
            repoUrl = "https://github.com/example/repo.git",
            repoPath = "/tmp/repo",
            subpath = "app",
            format = "cobertura",
            metric = "line-coverage",
            reportFile = reportFile
          )
        )
        assert(res == Result.Success("/v2/code-coverage/upload/abc-123"))
        val reqs = requests()
        assert(reqs.size == 2)
        val post = reqs(0)
        assert(post.headers("authorization") == "Bearer secret-token")
        assert(post.headers("content-type").startsWith("application/json"))
        val meta = ujson.read(post.bodyText)
        assert(meta("commit-sha").str == "0123456789abcdef0123456789abcdef01234567")
        assert(meta("repo-url").str == "https://github.com/example/repo.git")
        assert(meta("subpath").str == "app")
        assert(meta("format").str == "cobertura")
        assert(meta("metric").str == "line-coverage")

        val put = reqs(1)
        assert(put.headers("authorization") == "Bearer secret-token")
        assert(put.headers("content-type") == "application/octet-stream")
        assert(gunzip(put.rawBody) == "<coverage><packages/></coverage>")
      }

    test("upload - fails cleanly when report file does not exist"):
      val missing = os.temp.dir() / "missing.xml"
      val res     = CodeSceneCoverageUploader.upload(
        CodeSceneCoverageUploader.UploadRequest(
          apiBaseUrl = "http://127.0.0.1:1",
          token = "t",
          commitSha = "a" * 40,
          repoUrl = "https://github.com/example/repo.git",
          repoPath = "/tmp/repo",
          subpath = "",
          format = "cobertura",
          metric = "line-coverage",
          reportFile = missing
        )
      )
      assert(res.isInstanceOf[Result.Failure])

    test("upload - fails when POST returns non-2xx or missing ref"):
      val reportFile = os.temp("<coverage/>")
      withFakeServer {
        case r if r.method == "POST" => (401, """{"error":"unauthorized"}""")
      } { (baseUrl, _) =>
        val res = CodeSceneCoverageUploader.upload(
          CodeSceneCoverageUploader.UploadRequest(
            apiBaseUrl = baseUrl,
            token = "bad",
            commitSha = "a" * 40,
            repoUrl = "https://github.com/example/repo.git",
            repoPath = "/tmp/repo",
            subpath = "",
            format = "cobertura",
            metric = "line-coverage",
            reportFile = reportFile
          )
        )
        res match
          case f: Result.Failure => assert(f.error.contains("401"))
          case other             => throw new java.lang.AssertionError(s"Expected failure, got $other")
      }

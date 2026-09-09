package atn.mill

import com.sun.net.httpserver.{HttpExchange, HttpServer}

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/** One request a [[FakeApi]] served: method, path (query excluded), query string, lower-cased headers and body. */
final case class Received(
  method: String,
  path: String,
  query: Option[String],
  headers: Map[String, String],
  body: String
):
  /** The body parsed as JSON. */
  def json: ujson.Value = ujson.read(body)

/** Matches a [[Received]] request on its method and path: `case Route("GET", "/cs/projects") => ...`. */
object Route:
  def unapply(request: Received): Some[(String, String)] = Some((request.method, request.path))

/** A canned HTTP reply. */
final case class Reply(status: Int, body: String)

object Reply:
  /** A 200 reply carrying `value` as JSON. */
  def json(value: ujson.Value): Reply = Reply(200, ujson.write(value))

/**
 * An in-process HTTP server on an ephemeral loopback port that records every request and answers from `routes`;
 * unmatched requests get a 404. It stands in for CodeScene and Port.io in the devx tests, which never hit the network.
 */
final class FakeApi(routes: PartialFunction[Received, Reply]) extends AutoCloseable:
  private val server   = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
  private val received = new ConcurrentLinkedQueue[Received]

  server.createContext("/", (exchange: HttpExchange) => handle(exchange))
  server.start()

  /** Base URL of the server, without a trailing slash. */
  def url: String = s"http://127.0.0.1:${server.getAddress.getPort}"

  /** Every request served so far, oldest first. */
  def requests: List[Received] = received.asScala.toList

  def close(): Unit = server.stop(0)

  private def handle(exchange: HttpExchange): Unit =
    val uri     = exchange.getRequestURI
    val headers =
      exchange.getRequestHeaders.asScala.map((name, values) => name.toLowerCase -> values.asScala.mkString(",")).toMap
    val body    = new String(exchange.getRequestBody.readAllBytes(), UTF_8)
    val request = Received(exchange.getRequestMethod, uri.getPath, Option(uri.getQuery), headers, body)
    received.add(request)
    val reply   = routes.applyOrElse(request, (_: Received) => Reply(404, """{"error":"not found"}"""))
    val bytes   = reply.body.getBytes(UTF_8)
    exchange.getResponseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(reply.status, if bytes.isEmpty then -1L else bytes.length.toLong)
    if bytes.nonEmpty then exchange.getResponseBody.write(bytes)
    exchange.close()

object FakeApi:
  /** The CodeScene token the redirected client presents. */
  val CodeSceneToken = "cs-test-token"

  /** The Port.io client id the redirected client presents. */
  val PortClientId = "port-client-id"

  /** The Port.io client secret the redirected client presents. */
  val PortSecret = "port-secret"

  /** Everything [[withClients]] changes on the two client objects, so a scope can put it back. */
  final private case class Clients(
    csApi: String,
    csEnv: String => Option[String],
    portApi: String,
    portEnv: String => Option[String],
    cache: Option[PortIO.TokenCache]
  ):
    def install(): Unit =
      CodeScene.api = csApi
      CodeScene.getenv = csEnv
      PortIO.api = portApi
      PortIO.getenv = portEnv
      PortIO.accessTokenCache = cache

  private def current: Clients =
    Clients(CodeScene.api, CodeScene.getenv, PortIO.api, PortIO.getenv, PortIO.accessTokenCache)

  /** Runs `body` against a server answering `routes`, stopping it afterwards. */
  def withServer[A](routes: PartialFunction[Received, Reply])(body: FakeApi => A): A =
    val server = new FakeApi(routes)
    try body(server)
    finally server.close()

  /**
   * Runs `body` with [[CodeScene]] mounted at `/cs` and [[PortIO]] at `/port` of one fake server, credentials fixed to
   * the constants above and the Port.io token cache cleared; the previous client settings come back afterwards.
   */
  def withClients[A](routes: PartialFunction[Received, Reply])(body: FakeApi => A): A =
    withServer(routes) { server =>
      val previous = current
      Clients(
        s"${server.url}/cs",
        Map(CodeScene.csAccessTokenEnvVar -> CodeSceneToken).get,
        s"${server.url}/port",
        Map(PortIO.portClientIdEnvVar -> PortClientId, PortIO.portClientSecretEnvVar -> PortSecret).get,
        None
      ).install()
      try body(server)
      finally previous.install()
    }

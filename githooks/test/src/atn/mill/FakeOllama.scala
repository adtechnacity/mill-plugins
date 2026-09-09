package atn.mill

import com.sun.net.httpserver.{HttpExchange, HttpServer}

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.ConcurrentLinkedQueue

import scala.jdk.CollectionConverters.*

/**
 * An in-process stand-in for an Ollama server: every request to `/api/chat` is answered with one fixed status and body,
 * and the request bodies are kept so a test can check what the client sent.
 */
final class FakeOllama private (server: HttpServer, received: ConcurrentLinkedQueue[String]):

  /** Base URL to hand to [[GitPrepCommit]] as `ollamaUrl`. */
  def url: String = s"http://127.0.0.1:${server.getAddress.getPort}"

  /** The chat requests received so far, oldest first, parsed. */
  def chatRequests: List[ujson.Value] = received.asScala.toList.map(ujson.read(_))

  def stop(): Unit = server.stop(0)

object FakeOllama:

  /** The JSON Ollama returns for a non-streaming chat completion whose assistant message is `content`. */
  def chatReply(content: String): String =
    ujson.write(
      ujson.Obj(
        "model"          -> "qwen3-test",
        "created_at"     -> "2026-01-01T00:00:00Z",
        "message"        -> ujson.Obj("role" -> "assistant", "content" -> content),
        "done"           -> true,
        "total_duration" -> 42
      )
    )

  /** Runs `body` against a server answering every chat request with `status` and `reply`, then stops it. */
  def serving[T](status: Int, reply: String)(body: FakeOllama => T): T =
    val received = new ConcurrentLinkedQueue[String]()
    val server   = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      "/api/chat",
      (exchange: HttpExchange) =>
        received.add(new String(exchange.getRequestBody.readAllBytes(), UTF_8))
        val bytes = reply.getBytes(UTF_8)
        exchange.getResponseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.length.toLong)
        exchange.getResponseBody.write(bytes)
        exchange.close()
    )
    server.start()
    val fake     = new FakeOllama(server, received)
    try body(fake)
    finally fake.stop()

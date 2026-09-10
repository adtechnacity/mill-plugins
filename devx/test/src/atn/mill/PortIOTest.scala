package atn.mill

import utest.*
import org.scalacheck.Gen
import org.scalacheck.Prop.{forAll, propBoolean}
import upickle.{default => json}
import FakeApi.*
import DevxFixtures.{port, token}
import PropertyChecks.{checkProp, roundTrip}

import java.util.concurrent.atomic.AtomicLong

/**
 * [[PortIOClient]] against a [[FakeApi]]: credentials, the token exchange and its cache, and the bulk entity upload.
 */
object PortIOTest extends TestSuite:

  /** The credential accessors as (call, environment variable name). */
  private val credentials: Seq[(PortIOClient => String, String)] =
    Seq((_.clientId, PortIO.portClientIdEnvVar), (_.secret, PortIO.portClientSecretEnvVar))

  private val entities = List(PortIO.Entity("2", "Platform"), PortIO.Entity("3", "Data", icon = "Team", team = "core"))

  /** The token's lifetime in milliseconds. */
  private val ttl = token.expiresIn * 1000L

  /** The time a clocked client starts at. */
  private val t0 = 1_000_000L

  /** The fake Port.io, a [[FakePortIO]] of it reading `clock`, and the token the client fetched at [[t0]]. */
  final private case class Clocked(server: FakeApi, clock: AtomicLong, client: PortIOClient, first: String)

  /** Runs `body` with a client that fetched its token at [[t0]], so the test can move time towards the expiry. */
  private def clocked(body: Clocked => Unit): Unit =
    withServer(port) { server =>
      val clock  = new AtomicLong(t0)
      val client = new FakePortIO(server) { override protected def now: Long = clock.get }
      body(Clocked(server, clock, client, client.accessToken))
    }

  private val genObj: Gen[ujson.Obj] =
    Gen.mapOf(Gen.zip(Gen.identifier, Gen.alphaNumStr)).map(m => ujson.Obj.from(m.map((k, v) => k -> ujson.Str(v))))

  private val genEntity: Gen[PortIO.Entity] =
    for
      identifier <- Gen.identifier
      title      <- Gen.alphaNumStr
      icon       <- Gen.alphaNumStr
      team       <- Gen.alphaNumStr
      properties <- genObj
      relations  <- genObj
    yield PortIO.Entity(identifier, title, icon, team, properties, relations)

  private val genToken: Gen[PortIO.TokenResponse] =
    for
      ok          <- Gen.oneOf(true, false)
      accessToken <- Gen.identifier
      tokenType   <- Gen.alphaStr
      expiresIn   <- Gen.posNum[Int]
    yield PortIO.TokenResponse(ok, accessToken, tokenType, expiresIn)

  val tests = Tests:

    test("api - base URL is the Port.io v1 endpoint"):
      assert(PortIO.api == "https://api.port.io/v1")

    test("credentials - fail naming the variable when it is unset or empty"):
      for
        (call, name) <- credentials
        value        <- Seq(None, Some(""))
      do
        val client = new PortIOClient:
          override def getEnv(variable: String): Option[String] = value
        val error  = assertThrows[RuntimeException](call(client))
        assert(error.getMessage.contains(name))

    test("accessToken"):

      test("exchanges the credentials once and caches the token until it expires"):
        clocked { c =>
          c.clock.set(t0 + ttl - 1)
          assert(c.first == token.accessToken, c.client.accessToken == c.first)
          val request = c.server.requests match
            case List(only) => only
            case other      => throw new java.lang.AssertionError(s"expected one token request, got $other")
          assert(request.method == "POST", request.path == "/port/auth/access_token")
          assert(request.headers("content-type") == "application/json")
          assert(request.json == ujson.Obj("clientId" -> PortClientId, "clientSecret" -> PortSecret))
        }

      test("replaces an expired cached token"):
        clocked { c =>
          c.clock.set(t0 + ttl)
          assert(c.client.accessToken == c.first, c.server.requests.size == 2)
        }

    test("headers - carry the bearer token and the JSON content types"):
      withClients(port) { c =>
        val expected = Map(
          "Authorization" -> s"Bearer ${token.accessToken}",
          "Accept"        -> "application/json",
          "Content-Type"  -> "application/json"
        )
        assert(c.portIO.headers == expected)
      }

    test("upload_blueprint - posts the entities to the blueprint's bulk endpoint with the upsert flag"):
      val accepting: PartialFunction[Received, Reply] =
        case Route("POST", "/port/blueprints/bp/entities/bulk") => Reply(201, """{"ok":true}""")
      for upsert <- Seq(true, false) do
        withClients(port.orElse(accepting)) { c =>
          val response = c.portIO.upload_blueprint("bp", upsert, entities)
          val request  = c.requests.last
          assert(
            response.statusCode == 201,
            request.method == "POST",
            request.path == "/port/blueprints/bp/entities/bulk"
          )
          assert(request.query == Some(s"upsert=$upsert"), request.json("entities") == json.writeJs(entities))
          assert(request.headers("authorization") == s"Bearer ${token.accessToken}")
        }

    test("Entity - round-trips through JSON, defaults included"):
      checkProp(forAll(genEntity)(entity => (roundTrip(entity) == entity).label(s"round trip changed $entity")))
      assert(roundTrip(PortIO.Entity("1", "One")) == PortIO.Entity("1", "One", "", "", ujson.Obj(), ujson.Obj()))

    test("TokenResponse - round-trips through JSON"):
      checkProp(forAll(genToken)(response => (roundTrip(response) == response).label(s"round trip changed $response")))

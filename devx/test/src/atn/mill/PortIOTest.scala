package atn.mill

import utest.*
import org.scalacheck.Gen
import org.scalacheck.Prop.{forAll, propBoolean}
import upickle.{default => json}
import FakeApi.*
import DevxFixtures.{port, token}
import PropertyChecks.{checkProp, roundTrip}

/** [[PortIO]] against a [[FakeApi]]: credentials, the token exchange and its cache, and the bulk entity upload. */
object PortIOTest extends TestSuite:

  /** The credential accessors as (call, environment variable name). */
  private val credentials: Seq[(() => String, String)] =
    Seq((() => PortIO.clientId, PortIO.portClientIdEnvVar), (() => PortIO.secret, PortIO.portClientSecretEnvVar))

  private val entities = List(PortIO.Entity("2", "Platform"), PortIO.Entity("3", "Data", icon = "Team", team = "core"))

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

    test("credentials - fail naming the variable when it is unset or empty"):
      for
        (call, name) <- credentials
        value        <- Seq(None, Some(""))
      do
        withClients(port) { _ =>
          PortIO.getenv = _ => value
          val error = assertThrows[RuntimeException](call())
          assert(error.getMessage.contains(name))
        }

    test("accessToken"):

      test("exchanges the credentials once and caches the token until it expires"):
        withClients(port) { server =>
          val before  = System.currentTimeMillis()
          val first   = PortIO.accessToken
          val after   = System.currentTimeMillis()
          val second  = PortIO.accessToken
          assert(first == token.accessToken, second == first)
          val request = server.requests match
            case List(only) => only
            case other      => throw new java.lang.AssertionError(s"expected one token request, got $other")
          assert(request.method == "POST", request.path == "/port/auth/access_token")
          assert(request.headers("content-type") == "application/json")
          assert(request.json == ujson.Obj("clientId" -> PortClientId, "clientSecret" -> PortSecret))
          val ttl     = token.expiresIn * 1000L
          PortIO.accessTokenCache match
            case Some((expiry, cached)) => assert(cached == first, expiry >= before + ttl, expiry <= after + ttl)
            case None                   => throw new java.lang.AssertionError("token was not cached")
        }

      test("replaces an expired cached token"):
        withClients(port) { server =>
          PortIO.accessTokenCache = Some((0L, "stale"))
          assert(PortIO.accessToken == token.accessToken, server.requests.size == 1)
        }

    test("headers - carry the bearer token and the JSON content types"):
      withClients(port) { _ =>
        val expected = Map(
          "Authorization" -> s"Bearer ${token.accessToken}",
          "Accept"        -> "application/json",
          "Content-Type"  -> "application/json"
        )
        assert(PortIO.headers == expected)
      }

    test("upload_blueprint - posts the entities to the blueprint's bulk endpoint with the upsert flag"):
      val accepting: PartialFunction[Received, Reply] =
        case Route("POST", "/port/blueprints/bp/entities/bulk") => Reply(201, """{"ok":true}""")
      for upsert <- Seq(true, false) do
        withClients(port.orElse(accepting)) { server =>
          val response = PortIO.upload_blueprint("bp", upsert, entities)
          val request  = server.requests.last
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

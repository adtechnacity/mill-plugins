package atn.mill

import upickle.{default => json}
import PortIO.{Entity, TokenCache, TokenResponse}

/**
 * Client for the [[https://port.io Port.io]] REST API (v1): the client credentials are exchanged for a bearer token,
 * cached until it expires, and entities are uploaded in bulk to a blueprint. Every member is an overridable `def`:
 * [[PortIO]] is the client of the public deployment, reading its credentials from the process environment, and a build
 * that talks to another deployment (or a test that talks to a fake) overrides [[api]] and [[getEnv]] in a subclass.
 */
trait PortIOClient {

  /** Base URL of the Port.io REST API (v1). Override to point the client at another deployment or a test server. */
  def api: String = "https://api.port.io/v1"

  /** Environment variable name for the Port.io client ID. */
  def portClientIdEnvVar: String = "PORT_CLIENT_ID"

  /** Environment variable name for the Port.io client secret. */
  def portClientSecretEnvVar: String = "PORT_CLIENT_SECRET"

  /**
   * Environment lookup behind [[clientId]] and [[secret]]: the process environment (`System.getenv`). Override to take
   * the credentials from elsewhere, as a test substituting a fixed map does.
   */
  def getEnv(name: String): Option[String] = Option(System.getenv(name))

  /**
   * The current time in epoch milliseconds, which a cached token's expiry is checked against; a test can override it.
   */
  protected def now: Long = System.currentTimeMillis()

  /** The client ID from the environment variable named by [[portClientIdEnvVar]]; fails when unset or empty. */
  def clientId: String = getEnv(portClientIdEnvVar)
    .filter(_.nonEmpty)
    .getOrElse(throw new RuntimeException(s"Environment variable $portClientIdEnvVar is not set or empty"))

  /** The client secret from the environment variable named by [[portClientSecretEnvVar]]; fails when unset or empty. */
  def secret: String = getEnv(portClientSecretEnvVar)
    .filter(_.nonEmpty)
    .getOrElse(throw new RuntimeException(s"Environment variable $portClientSecretEnvVar is not set or empty"))

  // The memoised access token with its expiry: the client's one piece of mutable state, private to each instance.
  private var accessTokenCache: Option[TokenCache] = None

  /** A bearer token for the credentials: exchanged on the first call and reused until it expires. */
  def accessToken: String = accessTokenCache
    .filter(_._1 > now)
    .map(_._2)
    .getOrElse {
      val tokenData = requests.post(
        url = s"$api/auth/access_token",
        headers = Option("Content-Type" -> "application/json"),
        data = ujson.Obj("clientId" -> clientId, "clientSecret" -> secret)
      )
      val tokR      = json.read[TokenResponse](tokenData.text())
      accessTokenCache = Some(now + tokR.expiresIn * 1_000 -> tokR.accessToken)
      tokR.accessToken
    }

  /** The headers every request carries: bearer [[accessToken]] and the JSON content types. */
  def headers: Map[String, String] =
    Map("Authorization" -> s"Bearer $accessToken", "Accept" -> "application/json", "Content-Type" -> "application/json")

  /** Uploads `entities` to blueprint `id` in bulk, updating existing ones when `upsert`; returns the raw response. */
  def upload_blueprint(id: String, upsert: Boolean, entities: List[Entity]): requests.Response =
    requests.post(
      url = s"$api/blueprints/$id/entities/bulk?upsert=$upsert",
      headers = headers,
      data = s"{\"entities\": ${json.write(entities)} }"
    )
}

/** The [[PortIOClient]] of the public Port.io deployment, and the models it exchanges. */
object PortIO extends PortIOClient {

  case class TokenResponse(ok: Boolean, accessToken: String, tokenType: String, expiresIn: Int)
  object TokenResponse {
    implicit val rw: json.ReadWriter[TokenResponse] = json.macroRW
  }

  case class Entity(
    identifier: String,
    title: String,
    icon: String = "",
    team: String = "",
    properties: ujson.Obj = ujson.Obj(),
    relations: ujson.Obj = ujson.Obj()
  )
  object Entity {
    implicit val rw: json.ReadWriter[Entity] = json.macroRW
  }

  /** A cached access token as (expiry in epoch milliseconds, token). */
  type TokenCache = (Long, String)
}

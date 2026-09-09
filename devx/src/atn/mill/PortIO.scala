package atn.mill

import upickle.{default => json}

object PortIO {

  /** Environment variable name for Port.io client ID. Override via config if needed. */
  var portClientIdEnvVar: String = "PORT_CLIENT_ID"

  /** Environment variable name for Port.io client secret. Override via config if needed. */
  var portClientSecretEnvVar: String = "PORT_CLIENT_SECRET"

  /** Environment lookup behind [[clientId]] and [[secret]]: `System.getenv`, unless a test substitutes a fixed map. */
  var getenv: String => Option[String] = name => Option(System.getenv(name))

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

  /** Base URL of the Port.io REST API (v1). Reassignable so tests can point the client at a local server. */
  var api: String = "https://api.port.io/v1"

  def clientId = getenv(portClientIdEnvVar)
    .filter(_.nonEmpty)
    .getOrElse(throw new RuntimeException(s"Environment variable $portClientIdEnvVar is not set or empty"))

  def secret = getenv(portClientSecretEnvVar)
    .filter(_.nonEmpty)
    .getOrElse(throw new RuntimeException(s"Environment variable $portClientSecretEnvVar is not set or empty"))

  type TokenCache = (Long, String)

  var accessTokenCache: Option[TokenCache] = None

  def accessToken = accessTokenCache
    .filter(_._1 > System.currentTimeMillis())
    .map(_._2)
    .getOrElse {
      val tokenData = requests.post(
        url = s"$api/auth/access_token",
        headers = Option("Content-Type" -> "application/json"),
        data = ujson.Obj("clientId" -> clientId, "clientSecret" -> secret)
      )
      val tokR      = json.read[TokenResponse](tokenData.text())
      accessTokenCache = Some(
        System.currentTimeMillis() + tokR.expiresIn * 1_000 ->
          tokR.accessToken
      )
      tokR.accessToken
    }

  def headers =
    Map("Authorization" -> s"Bearer $accessToken", "Accept" -> "application/json", "Content-Type" -> "application/json")

  def upload_blueprint(id: String, upsert: Boolean, entities: List[Entity]) =
    requests.post(
      url = s"$api/blueprints/$id/entities/bulk?upsert=$upsert",
      headers = headers,
      data = s"{\"entities\": ${json.write(entities)} }"
    )

}

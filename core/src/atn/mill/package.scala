package atn.mill

import mill.api.daemon.Result

import scala.util.Try

extension (res: => Result[String])
  def orEnv(env: String, default: String = "<n/a>"): String = Try(res.toOption).toOption.flatten
    .orElse(Option(System.getenv(env)))
    .getOrElse(default)

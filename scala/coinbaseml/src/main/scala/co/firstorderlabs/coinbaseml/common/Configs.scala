package co.firstorderlabs.coinbaseml.common

import scala.util.Properties.envOrElse

object Configs {
  val environment = if (envOrElse("ENVIRONMENT", "staging") == "prod") "prod" else "staging"
}

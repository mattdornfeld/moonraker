package co.firstorderlabs.coinbaseml.common

import java.util.logging.Level
import scala.util.Properties.envOrElse

object Configs {
  val environment =
    if (envOrElse("ENVIRONMENT", "staging") == "prod") "prod" else "staging"
  var testMode = false
  def logLevel: Level = if (testMode) Level.SEVERE else Level.INFO
}

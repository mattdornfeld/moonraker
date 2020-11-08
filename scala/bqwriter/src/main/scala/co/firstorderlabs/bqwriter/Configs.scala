package co.firstorderlabs.bqwriter

import java.io.{File, FileInputStream}

import com.google.auth.oauth2.ServiceAccountCredentials
import org.knowm.xchange.currency.CurrencyPair

import scala.util.Properties.envOrElse

object Configs {
  private object Environment {
    val prod = "prod"
    val staging = "staging"
  }

  val channelId = CurrencyPair.BTC_USD
  val environment =
    if (List(Environment.prod, "master").contains(envOrElse("ENVIRONMENT", Environment.staging)))
      Environment.prod
    else Environment.staging
  val datasetId = Map(
    Environment.prod -> "exchange_events_prod",
    Environment.staging -> "exchange_events_staging"
  )(environment)
  val gcpProjectId = "moonraker"
  val maxSequenceTrackerSize = 10000
  val maxEventBufferSize = envOrElse("MAX_EVENT_BUFFER_SIZE", "200").toInt
  var testMode = false

  val credentialsFile = new File("/var/moonraker/bqwriter/service-account.json")
  if (testMode) { credentialsFile.createNewFile }
  lazy val serviceAccountJsonPath =
    ServiceAccountCredentials.fromStream(new FileInputStream(credentialsFile))
}

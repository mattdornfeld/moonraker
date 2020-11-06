package co.firstorderlabs.bqwriter

import java.io.{File, FileInputStream}

import com.google.auth.oauth2.ServiceAccountCredentials
import org.knowm.xchange.currency.CurrencyPair

import scala.util.Properties.envOrElse

object Configs {
  val channelId = CurrencyPair.BTC_USD
  val datasetId = envOrElse("BIGQUERY_DATASET_ID", "exchange_events_staging")
  val gcpProjectId = "moonraker"
  val maxSequenceTrackerSize = 10000
  val maxEventBufferSize = envOrElse("MAX_EVENT_BUFFER_SIZE", "200").toInt
  var testMode = false

  val credentialsFile = new File("/secrets/service-account.json")
  if (testMode) { credentialsFile.createNewFile }
  lazy val serviceAccountJsonPath =
    ServiceAccountCredentials.fromStream(new FileInputStream(credentialsFile))
}

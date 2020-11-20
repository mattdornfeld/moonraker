package co.firstorderlabs.coinbaseml.fakebase.sql

import java.nio.file.Paths
import java.time.Duration

import swaydb.data.util.StorageUnits.StorageDoubleImplicits

import scala.util.Properties.envOrElse

object Configs {
  //BigQueryReader configs
  val serviceAccountJsonPath = "/var/moonraker/coinbaseml/service-account.json"
  val datasetId = "exchange_events_prod"
  val gcpProjectId = "moonraker"
  val queryTimeout = 60 * 60 // in seconds
  val serviceAccountEmail = "coinbase-train-ci@moonraker.iam.gserviceaccount.com"
  val bigQueryReadTimeDelta = Duration.ofMinutes(60) // amount of data to read in each call 

  //PostgresReader configs
  val maxResultsQueueSize = envOrElse("MAX_RESULTS_QUEUE_SIZE", "50").toInt
  val postgresDbHost = envOrElse("POSTGRES_HOST", "postgres")
  val postgresPassword = envOrElse("POSTGRES_PASSWORD", "password")
  val postgresTable = "moonraker"
  val postgresUsername = "postgres"
  val postgresReaderTimeout = envOrElse("POSTGRES_READER_TIMEOUT", "20000").toInt

  //DatabaseReader configs
  val numDatabaseReaderThreads = envOrElse("NUM_DATABASE_READER_THREADS", "4").toInt

  //SwayDb configs
  val swayDbPath = Paths.get("/tmp/moonraker/coinbaseml/swaydb")
  val swayDbMapSize = 1000.mb
}

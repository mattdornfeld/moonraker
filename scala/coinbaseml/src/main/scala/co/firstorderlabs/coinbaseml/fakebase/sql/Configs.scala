package co.firstorderlabs.coinbaseml.fakebase.sql

import java.time.Duration

import scala.util.Properties.envOrElse

object Configs {
  //BigQueryReader configs
  val bigQueryReadTimeDelta = Duration.ofMinutes( envOrElse("BIGQUERY_READ_TIME_DELTA", "10").toInt) // amount of data to read in each call
  val datasetId = "exchange_events_prod"
  val gcpProjectId = "moonraker"
  val queryTimeout = 60 * 60 // in seconds
  val serviceAccountEmail = "coinbase-train-ci@moonraker.iam.gserviceaccount.com"
  val serviceAccountJsonPath = "/var/moonraker/coinbaseml/service-account.json"

  //PostgresReader configs
  val postgresDbHost = envOrElse("POSTGRES_HOST", "postgres")
  val postgresPassword = envOrElse("POSTGRES_PASSWORD", "password")
  val postgresTable = "moonraker"
  val postgresUsername = "postgres"

  //DatabaseReader configs
  val maxResultsQueueSize = envOrElse("MAX_RESULTS_QUEUE_SIZE", "50").toInt
  val numDatabaseReaderThreads = envOrElse("NUM_DATABASE_READER_THREADS", "8").toInt
  val numLocalStorageReaderThreads = envOrElse("NUM_LOCAL_STORAGE_READER_THREADS", "2").toInt
  var queryResultMapMaxOverflow = 20

  //LocalStorage configs
  val localStoragePath = "/tmp/moonraker/coinbaseml/local_storage"
}

package co.firstorderlabs.coinbaseml.fakebase.sql

import scala.util.Properties.envOrElse

object Configs {
  //BigQueryReader configs
  val serviceAccountJsonPath = "/var/moonraker/coinbaseml/service-account.json"
  val datasetId = "exchange_events_prod"
  val gcpProjectId = "moonraker"
  val serviceAccountEmail = "coinbase-train-ci@moonraker.iam.gserviceaccount.com"

  //PostgresReader configs
  val maxResultsQueueSize = envOrElse("MAX_RESULTS_QUEUE_SIZE", "50").toInt
  val postgresDbHost = envOrElse("POSTGRES_HOST", "postgres")
  val postgresPassword = envOrElse("POSTGRES_PASSWORD", "password")
  val postgresTable = "moonraker"
  val postgresUsername = "postgres"
  val postgresReaderTimeout = envOrElse("POSTGRES_READER_TIMEOUT", "20000").toInt

  //DatabaseReader configs
  val numDatabaseWorkers = envOrElse("NUM_DATABASE_WORKERS", "4").toInt
}

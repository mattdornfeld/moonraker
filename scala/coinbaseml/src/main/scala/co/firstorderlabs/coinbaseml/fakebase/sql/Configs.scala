package co.firstorderlabs.coinbaseml.fakebase.sql

import java.time.Duration

import co.firstorderlabs.coinbaseml.common.{Configs => CommonConfigs}

import scala.util.Properties.envOrElse

object Configs {
  //BigQueryReader configs
  val bigQueryReadTimeDelta = Duration.ofMinutes( envOrElse("BIGQUERY_READ_TIME_DELTA", "30").toInt) // amount of data to read in each call
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
  val maxQueryResultMapSize = envOrElse("MAX_QUERY_RESULT_MAP_SIZE", "50").toInt
  val numDatabaseReaderThreads = envOrElse("NUM_DATABASE_READER_THREADS", "20").toInt
  val numLocalStorageReaderThreads = envOrElse("NUM_LOCAL_STORAGE_READER_THREADS", "2").toInt
  var queryResultMapMaxOverflow = 20

  //LocalStorage configs
  val forcePullFromDatabase = List("True", "TRUE", "true").contains(envOrElse("FORCE_PULL_FROM_DATABASE", "false"))
  val localStoragePath = "/tmp/moonraker/coinbaseml/local_storage"
  val sstFilesPath = "/tmp/moonraker/coinbaseml/sst_files"
  val sstBackupGcsBucket = "coinbaseml-train-sst-backup"
  val sstBackupBasePath = s"${CommonConfigs.environment}/query-results"
}

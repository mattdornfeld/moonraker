package co.firstorderlabs.fakebase

import scala.util.Properties.envOrElse

object Configs {
  var testMode = false

  //Database configs
  val maxResultsQueueSize = envOrElse("MAX_RESULTS_QUEUE_SIZE", "50").toInt
  val numDatabaseWorkers = envOrElse("NUM_DATABASE_WORKERS", "4").toInt
  val postgresDbHost = envOrElse("POSTGRES_HOST", "postgres")
  val postgresPassword = envOrElse("POSTGRES_PASSWORD", "password")
  val postgresTable = "moonraker"
  val postgresUsername = "postgres"
}

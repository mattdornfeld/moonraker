package co.firstorderlabs.fakebase

import scala.util.Properties.envOrElse

object Configs {
  var testMode = false

  //Database configs
  val maxResultsQueueSize = 50
  val numDatabaseWorkers = 4
  val postgresDbHost = envOrElse("DB_HOST", "localhost")
  val postgresPassword = envOrElse("POSTGRES_PASSWORD", "password")
  val postgresTable = "moonraker"
  val postgresUsername = "postgres"
}

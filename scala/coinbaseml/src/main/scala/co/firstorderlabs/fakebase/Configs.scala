package co.firstorderlabs.fakebase

import java.math.BigDecimal

import co.firstorderlabs.fakebase.protos.fakebase.{Liquidity, StepRequest}
import com.google.protobuf.empty.Empty

import scala.util.Properties.envOrElse

object Configs {
  var isTest = false

  //Database configs
  val maxResultsQueueSize = 50
  val numDatabaseWorkers = 4
  val postgresDbHost = envOrElse("DB_HOST", "localhost")
  val postgresPassword = envOrElse("POSTGRES_PASSWORD", "password")
  val postgresTable = "moonraker"
  val postgresUsername = "postgres"

  val feeFraction = Map[Liquidity, BigDecimal](
    Liquidity.maker -> new BigDecimal("0.005"),
    Liquidity.taker -> new BigDecimal("0.005")
  )

  val emptyProto = new Empty
  val emptyStepRequest = new StepRequest
}

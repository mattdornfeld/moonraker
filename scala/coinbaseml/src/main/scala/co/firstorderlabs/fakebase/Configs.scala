package co.firstorderlabs.fakebase

import java.math.BigDecimal
import java.time.{Duration, Instant}

import co.firstorderlabs.fakebase.protos.fakebase.Liquidity
import co.firstorderlabs.fakebase.types.Types.Datetime

import scala.util.Properties.envOrElse

object Configs {
  //Database configs
  val maxResultsQueueSize = 100
  val postgresDbHost = envOrElse("DB_HOST", "localhost")
  val postgresPassword = envOrElse("POSTGRES_PASSWORD", "password")
  val postgresTable = "moonraker"
  val postgresUsername = "postgres"

  //Time configs
  val timeDelta = Duration.ofMinutes(30)
  val startTime = Datetime(Instant.parse("2019-11-20T19:20:50.63Z"))
  val endTime = Datetime(Instant.parse("2019-11-21T20:40:50.63Z"))

  val feeFraction = Map[Liquidity, BigDecimal](
    Liquidity.maker -> new BigDecimal("0.005"),
    Liquidity.taker -> new BigDecimal("0.005")
  )
}

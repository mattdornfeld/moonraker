package co.firstorderlabs.coinbaseml.fakebase.sql

import java.time.{Duration, Instant}

import co.firstorderlabs.coinbaseml.common.protos.ObservationRequest
import co.firstorderlabs.coinbaseml.fakebase.protos.fakebase.SimulationStartRequest
import co.firstorderlabs.common.currency.Price.BtcUsdPrice.{ProductVolume, QuoteVolume}
import co.firstorderlabs.common.types.Types.TimeInterval

object TestData {
  val startTime = Instant.parse("2019-11-20T00:00:00.0Z")
  val endTime = Instant.parse("2019-11-20T00:10:00.0Z")
  val timeDelta = Duration.ofMinutes(1)
  val expectedTimeIntervals =
    TimeInterval(startTime, endTime).chunkByTimeDelta(timeDelta)

  val simulationStartRequest = new SimulationStartRequest(
    startTime,
    endTime,
    Some(timeDelta),
    3,
    new ProductVolume(Right("100.000000")),
    new QuoteVolume(Right("10000.00")),
    snapshotBufferSize = 3,
    observationRequest = Some(new ObservationRequest(10)),
  )
}

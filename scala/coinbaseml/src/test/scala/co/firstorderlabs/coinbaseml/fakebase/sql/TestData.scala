package co.firstorderlabs.coinbaseml.fakebase.sql

import java.time.{Duration, Instant}

import co.firstorderlabs.coinbaseml.fakebase.SimulationMetadata
import co.firstorderlabs.common.currency.Configs.ProductPrice.{ProductVolume, QuoteVolume}
import co.firstorderlabs.common.protos.environment.ObservationRequest
import co.firstorderlabs.common.protos.fakebase.SimulationStartRequest
import co.firstorderlabs.common.types.Types.TimeInterval

object TestData {
  val startTime = Instant.parse("2020-11-09T00:00:00.0Z")
  val endTime = Instant.parse("2020-11-09T00:10:00.0Z")
  val timeDelta = Duration.ofMinutes(1)
  val expectedTimeIntervals =
    TimeInterval(startTime, endTime).chunkBy(timeDelta)

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

  implicit val simulationMetadata = SimulationMetadata.fromSimulationStartRequest(simulationStartRequest)
}

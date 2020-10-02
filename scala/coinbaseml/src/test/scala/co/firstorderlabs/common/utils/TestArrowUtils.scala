package co.firstorderlabs.common.utils

import java.time.{Duration, Instant}

import co.firstorderlabs.common.Environment
import co.firstorderlabs.common.types.FeaturesBase
import co.firstorderlabs.fakebase.TestData.RequestsData.observationRequest
import co.firstorderlabs.fakebase.currency.Price.BtcUsdPrice.{ProductVolume, QuoteVolume}
import co.firstorderlabs.fakebase.protos.fakebase.SimulationStartRequest
import co.firstorderlabs.fakebase.{Configs, Exchange}
import org.scalatest.funspec.AnyFunSpec

class TestArrowUtils extends AnyFunSpec{
  Configs.testMode = true

  describe("ArrowUtils") {
    it("A Feature object should be writable to a group of Arrow socket files. It should also be " +
      "re-constructable from those files.") {
      val simulationStartRequest = new SimulationStartRequest(
        Instant.parse("2019-11-20T19:20:50.63Z"),
        Instant.parse("2019-11-20T19:25:50.63Z"),
        Some(Duration.ofSeconds(1)),
        5,
        new ProductVolume(Right("100.000000")),
        new QuoteVolume(Right("10000.00")),
        snapshotBufferSize = 5,
        observationRequest = Some(observationRequest),
      )

      Exchange.start(simulationStartRequest)
      TestUtils.advanceExchangeAndPlaceOrders
      val writeFeatures = Environment.construct(observationRequest)
      writeFeatures.writeToSockets
      val readFeatures = FeaturesBase.fromArrowSockets

      assert(writeFeatures == readFeatures)
    }
  }
}

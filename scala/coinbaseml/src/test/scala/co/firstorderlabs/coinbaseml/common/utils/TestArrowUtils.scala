package co.firstorderlabs.coinbaseml.common.utils

import java.time.{Duration, Instant}

import co.firstorderlabs.coinbaseml.common.Environment
import co.firstorderlabs.coinbaseml.common.featurizers.TimeSeriesOrderBook
import co.firstorderlabs.coinbaseml.common.utils.ArrowUtils.ArrowFeatures
import co.firstorderlabs.coinbaseml.common.utils.Utils.getResult
import co.firstorderlabs.coinbaseml.fakebase.TestData.RequestsData.observationRequest
import co.firstorderlabs.coinbaseml.fakebase.{Configs, Exchange, SimulationState}
import co.firstorderlabs.common.currency.Price.BtcUsdPrice.{ProductVolume, QuoteVolume}
import co.firstorderlabs.common.protos.fakebase.SimulationStartRequest
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
        stopInProgressSimulations = true,
      )

      val simulationInfo = getResult(Exchange.start(simulationStartRequest))
      implicit val simulationState = SimulationState.getOrFail(simulationInfo.simulationId.get)
      implicit val simulationMetadata = simulationState.simulationMetadata
      TestUtils.advanceExchangeAndPlaceOrders
      val writeFeatures = Environment.construct(observationRequest)
      writeFeatures.writeToSocket
      val readFeatures = TimeSeriesOrderBook.constructFromArrowSocket

      assert(writeFeatures == readFeatures)
    }
  }
}

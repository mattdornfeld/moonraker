package co.firstorderlabs.coinbaseml.fakebase

import co.firstorderlabs.coinbaseml.common.Configs.testMode
import co.firstorderlabs.coinbaseml.common.utils.TestUtils.buildStepRequest
import co.firstorderlabs.coinbaseml.common.utils.Utils.getResult
import co.firstorderlabs.coinbaseml.fakebase.TestData.OrdersData
import co.firstorderlabs.coinbaseml.fakebase.TestData.RequestsData._
import co.firstorderlabs.common.currency.Configs.ProductPrice
import co.firstorderlabs.common.currency.Configs.ProductPrice.ProductVolume
import co.firstorderlabs.common.protos.fakebase.StepRequest
import co.firstorderlabs.common.types.Types.SimulationId
import org.scalatest.funspec.AnyFunSpec

class CheckpointerTest extends AnyFunSpec {
  testMode = true

  describe("Checkpointer") {
    it(
      "If simulationStartRequest.numWarmUpSteps > 0, then a checkpoint should be created. After the simulation advances," +
        "when reset, it should return to that checkpoint."
    ) {
      val simulationId = getResult(Exchange.start(checkpointedSimulationStartRequest)).simulationId.get

      assert(SimulationState.getSnapshot(simulationId).nonEmpty)

      val stepRequest = buildStepRequest(simulationId)
      (1 to 5) foreach (_ => Exchange.step(stepRequest))

      Exchange.reset(simulationId.toObservationRequest)

      assert(SimulationState.get(simulationId).get == SimulationState.getSnapshot(simulationId).get)
    }

    it(
      "No matter what orders are placed on the order book after a checkpoint is created, the simulation state should" +
        "return to the checkpoint state when reset is called."
    ) {
      val simulationId = getResult(Exchange.start(checkpointedSimulationStartRequest)).simulationId.get
      implicit val simulationMetadata = SimulationState.getSimulationMetadataOrFail(simulationId)
      advanceExchange(simulationId)
      Exchange.reset(simulationId.toObservationRequest)

      assert(SimulationState.get(simulationId).get == SimulationState.getSnapshot(simulationId).get)
    }

    it(
      "If a simulation is started then stopped, its state should be cleared completely and DatabaseWorkers should enter a paused state."
    ) {
      val simulationId = getResult(Exchange.start(checkpointedSimulationStartRequest)).simulationId.get
      implicit val simulationMetadata = SimulationState.getSimulationMetadataOrFail(simulationId)
      advanceExchange(simulationId)
      Exchange.stop(simulationId)
      assert(SimulationState.get(simulationId).isEmpty)
      assert(SimulationState.getSnapshot(simulationId).isEmpty)
    }
  }
  def advanceExchange(simulationId: SimulationId)(implicit simulationMetadata: SimulationMetadata): Unit = {
    Exchange step StepRequest(
      insertOrders = OrdersData.insertSellOrders(
        new ProductPrice(Right("100.00")),
        new ProductVolume(Right("0.5"))
      ) ++ OrdersData.insertBuyOrders(new ProductPrice(Right("100.00"))),
      simulationId = Some(simulationId),
    )

    Account.placeBuyMarketOrder(buyMarketOrderRequest(simulationId))

    Exchange.checkpoint(simulationId)

    Exchange step StepRequest(
      simulationId = Some(simulationId),
      insertOrders = OrdersData.insertSellOrders(
        new ProductPrice(Right("100.00")),
        new ProductVolume(Right("0.5"))
      ) ++ OrdersData.insertBuyOrders(new ProductPrice(Right("100.00")))
    )

    Account.placeBuyMarketOrder(buyMarketOrderRequest(simulationId))
  }
}

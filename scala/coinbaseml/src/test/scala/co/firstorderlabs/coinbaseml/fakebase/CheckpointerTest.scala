package co.firstorderlabs.coinbaseml.fakebase

import co.firstorderlabs.coinbaseml.fakebase.TestData.OrdersData
import co.firstorderlabs.coinbaseml.fakebase.TestData.RequestsData._
import co.firstorderlabs.coinbaseml.fakebase.sql.PostgresReader
import co.firstorderlabs.common.currency.Configs.ProductPrice
import co.firstorderlabs.common.currency.Configs.ProductPrice.ProductVolume
import co.firstorderlabs.common.protos.fakebase.{SimulationInfoRequest, StepRequest}
import org.scalatest.funspec.AnyFunSpec

class CheckpointerTest extends AnyFunSpec {
  Configs.testMode = true

  describe("Checkpointer") {
    it(
      "If simulationStartRequest.numWarmUpSteps > 0, then a checkpoint should be created. After the simulation advances," +
        "when reset, it should return to that checkpoint."
    ) {
      Exchange.start(checkpointedSimulationStartRequest)

      assert(Checkpointer.checkpointExists)

      (1 to 5) foreach (_ => Exchange.step(Constants.emptyStepRequest))

      Exchange.reset(SimulationInfoRequest())

      assert(Checkpointer.checkpointSnapshot == Checkpointer.createSnapshot)
    }

    it(
      "No matter what orders are placed on the order book after a checkpoint is created, the simulation state should" +
        "return to the checkpoint state when reset is called."
    ) {
      Exchange.start(simulationStartRequestWarmup)
      val expectedCheckpointSnapshotBuffer = advanceExchange
      Exchange.reset(SimulationInfoRequest())

      assert(Checkpointer.checkpointSnapshot == Checkpointer.createSnapshot)
    }

    it(
      "If a simulation is started then stopped, its state should be cleared completely and DatabaseWorkers should enter a paused state."
    ) {
      Exchange.start(simulationStartRequestWarmup)
      advanceExchange
      Exchange.stop(Constants.emptyProto)
      assert(Checkpointer.isCleared)
      assert(PostgresReader.blockUntilWaiting)
    }
  }
  def advanceExchange: SimulationSnapshot = {
    Exchange step StepRequest(
      insertOrders = OrdersData.insertSellOrders(
        new ProductPrice(Right("100.00")),
        new ProductVolume(Right("0.5"))
      ) ++ OrdersData.insertBuyOrders(new ProductPrice(Right("100.00")))
    )

    Account.placeBuyMarketOrder(buyMarketOrderRequest)

    Exchange.checkpoint(Constants.emptyProto)
    val simulationSnapshot = Checkpointer.simulationSnapshot

    Exchange step StepRequest(
      insertOrders = OrdersData.insertSellOrders(
        new ProductPrice(Right("100.00")),
        new ProductVolume(Right("0.5"))
      ) ++ OrdersData.insertBuyOrders(new ProductPrice(Right("100.00")))
    )

    Account.placeBuyMarketOrder(buyMarketOrderRequest)
    simulationSnapshot.get
  }
}

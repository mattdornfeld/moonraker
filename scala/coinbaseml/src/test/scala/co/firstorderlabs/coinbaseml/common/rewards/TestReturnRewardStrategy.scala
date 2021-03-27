package co.firstorderlabs.coinbaseml.common.rewards

import co.firstorderlabs.coinbaseml.common.Configs.testMode
import co.firstorderlabs.coinbaseml.common.utils.TestUtils.buildStepRequest
import co.firstorderlabs.coinbaseml.common.utils.Utils.getResult
import co.firstorderlabs.coinbaseml.fakebase.TestData.RequestsData._
import co.firstorderlabs.coinbaseml.fakebase._
import co.firstorderlabs.common.currency.Configs.ProductPrice
import co.firstorderlabs.common.currency.Configs.ProductPrice.{ProductVolume, QuoteVolume}
import co.firstorderlabs.common.protos.environment.InfoDictKey
import co.firstorderlabs.common.protos.events.OrderSide
import co.firstorderlabs.common.protos.fakebase.{CancellationRequest, StepRequest}
import co.firstorderlabs.common.types.Types.SimulationId
import org.scalatest.funspec.AnyFunSpec

class TestReturnRewardStrategy extends AnyFunSpec {
  testMode = true
  describe("ReturnRewardStrategy") {
    it(
      "The total portfolio value should equal the total quote funds in the account if no orders are " +
        "placed on the order book"
    ) {
      val simulationInfo = getResult(Exchange.start(simulationStartRequest))
      val simulationState = SimulationState.getOrFail(simulationInfo.simulationId.get)
      implicit val matchingEngineState = simulationState.matchingEngineState
      implicit val walletsState = simulationState.accountState.walletsState
      val stepRequest = buildStepRequest(simulationInfo.simulationId.get)
      (1 to 2) foreach (_ => Exchange.step(stepRequest))
      assert(
        ReturnRewardStrategy.currentPortfolioValue == Wallets.getWallet(QuoteVolume).balance.toDouble
      )
    }

    it(
      "The mid price should equal the average of the best bid and ask prices on the order book"
    ) {
      val simulationId = getResult(Exchange.start(simulationStartRequest)).simulationId.get
      val simulationState = SimulationState.getOrFail(simulationId)
      implicit val matchingEngineState = simulationState.matchingEngineState
      TestReturnRewardStrategy.populateOrderBook(simulationId)
      val expectedMidPrice =
        ((buyLimitOrderRequest(simulationId).price + sellLimitOrderRequest(simulationId).price) / Right(
          2.0
        )).toDouble

      assert(
        expectedMidPrice == ReturnRewardStrategy.calcMidPrice
      )
    }

    it(
      "The total portfolio value should equal the total quote funds in the account plus the value of the product funds " +
        "at the price when orders are placed on the order book"
    ) {
      val simulationInfo = getResult(Exchange.start(simulationStartRequest))
      val simulationState = SimulationState.getOrFail(simulationInfo.simulationId.get)
      implicit val matchingEngineState = simulationState.matchingEngineState
      implicit val walletsState = simulationState.accountState.walletsState
      TestReturnRewardStrategy.populateOrderBook(simulationInfo.simulationId.get)
      val midPrice = ReturnRewardStrategy.calcMidPrice
      val productVolume = Wallets.getWallet(ProductVolume).balance.toDouble
      val quoteVolume = Wallets.getWallet(QuoteVolume).balance.toDouble
      val expectedPortfolioValue = quoteVolume + midPrice * productVolume

      assert(
        expectedPortfolioValue == ReturnRewardStrategy.currentPortfolioValue
      )
    }

    it("The reward calculated by ReturnRewardStrategy should be the difference in portfolio value " +
      "between two consecutive time steps") {
      val simulationId = getResult(Exchange.start(simulationStartRequest)).simulationId.get
      implicit val simulationState = SimulationState.getOrFail(simulationId)
      val accountState = simulationState.accountState
      implicit val walletsState = accountState.walletsState
      implicit val matchingEngineState = simulationState.matchingEngineState
      TestReturnRewardStrategy.populateOrderBook(simulationId)
      val portfolioValue1 = ReturnRewardStrategy.currentPortfolioValue
      val productValue = portfolioValue1 - Wallets.getWallet(QuoteVolume).balance.toDouble
      accountState.placedOrders.keys.foreach(orderId => Account.cancelOrder(new CancellationRequest(orderId, Some(simulationId))))
      Exchange.step(buildStepRequest(simulationId))
      val portfolioValue2 = ReturnRewardStrategy.currentPortfolioValue
      val expectedReward = (portfolioValue2 - portfolioValue1)

      assert(expectedReward == ReturnRewardStrategy.calcReward)
      assert(expectedReward == -productValue)
    }

    it("") {
      val simulationInfo = getResult(Exchange.start(simulationStartRequest))
      val simulationState = SimulationState.getOrFail(simulationInfo.simulationId.get)
      val simulationMetadata = simulationState.simulationMetadata
      val infoDict = SimulationState.getInfoDictOrFail(simulationInfo.simulationId.get)
      implicit val matchingEngineState = simulationState.matchingEngineState
      TestReturnRewardStrategy.populateOrderBook(simulationInfo.simulationId.get)
      Exchange.checkpoint(simulationInfo.simulationId.get)
      val portfolioValue = ReturnRewardStrategy.currentPortfolioValue
      println(portfolioValue)

      val buyOrders = TestUtils
        .generateOrdersForRangeOfPrices(
          new ProductPrice(Right("1.00")),
          new ProductPrice(Right("1002.00")),
          new ProductPrice(Right("1005.00")),
          OrderSide.buy,
          new ProductVolume(Right("1.00")),
          simulationMetadata.currentTimeInterval.endTime
        )

      Exchange.step(new StepRequest(insertOrders = buyOrders, simulationId = simulationInfo.simulationId))
      println(ReturnRewardStrategy.currentPortfolioValue)
      Exchange.reset(simulationInfo.simulationId.get.toObservationRequest)
      println(ReturnRewardStrategy.currentPortfolioValue)
      println(infoDict(InfoDictKey.portfolioValue))
    }
  }
}

object TestReturnRewardStrategy {
  def populateOrderBook(simulationId: SimulationId): Unit = {
    Account.placeBuyLimitOrder(buyLimitOrderRequest(simulationId))
    Account.placeSellLimitOrder(sellLimitOrderRequest(simulationId))
    Exchange.step(buildStepRequest(simulationId))
  }
}

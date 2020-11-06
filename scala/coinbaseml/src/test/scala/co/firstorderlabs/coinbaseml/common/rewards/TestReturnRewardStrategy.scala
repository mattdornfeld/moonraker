package co.firstorderlabs.coinbaseml.common.rewards

import co.firstorderlabs.coinbaseml.fakebase.TestData.RequestsData._
import co.firstorderlabs.coinbaseml.fakebase._
import co.firstorderlabs.common.currency.Configs.ProductPrice.{ProductVolume, QuoteVolume}
import co.firstorderlabs.common.protos.fakebase.CancellationRequest
import org.scalatest.funspec.AnyFunSpec

class TestReturnRewardStrategy extends AnyFunSpec {
  Configs.testMode = true
  describe("ReturnRewardStrategy") {
    it(
      "The total portfolio value should equal the total quote funds in the account if no orders are " +
        "placed on the order book"
    ) {
      Exchange.start(simulationStartRequest)
      (1 to 2) foreach (_ => Exchange.step(Constants.emptyStepRequest))
      assert(
        ReturnRewardStrategy.calcPortfolioValue(
          Exchange.getSimulationMetadata.currentTimeInterval
        ) == Wallets.getWallet(QuoteVolume).balance.toDouble
      )
    }

    it(
      "The mid price should equal the average of the best bid and ask prices on the order book"
    ) {
      Exchange.start(simulationStartRequest)
      TestReturnRewardStrategy.populateOrderBook
      val expectedMidPrice =
        ((buyLimitOrderRequest.price + sellLimitOrderRequest.price) / Right(
          2.0
        )).toDouble

      assert(
        expectedMidPrice == ReturnRewardStrategy
          .calcMidPrice(Exchange.getSimulationMetadata.currentTimeInterval)
      )
    }

    it(
      "The total portfolio value should equal the total quote funds in the account plus the value of the product funds " +
        "at the price when orders are placed on the order book"
    ) {
      Exchange.start(simulationStartRequest)
      TestReturnRewardStrategy.populateOrderBook
      val currentTimeInterval =
        Exchange.getSimulationMetadata.currentTimeInterval
      val midPrice = ReturnRewardStrategy.calcMidPrice(currentTimeInterval)
      val productVolume = Wallets.getWallet(ProductVolume).balance.toDouble
      val quoteVolume = Wallets.getWallet(QuoteVolume).balance.toDouble
      val expectedPortfolioValue = quoteVolume + midPrice * productVolume

      assert(
        expectedPortfolioValue == ReturnRewardStrategy
          .calcPortfolioValue(currentTimeInterval)
      )
    }

    it("The reward calculated by ReturnRewardStrategy should be the difference in portfolio value " +
      "between two consecutive time steps") {
      Exchange.start(simulationStartRequest)
      TestReturnRewardStrategy.populateOrderBook
      val portfolioValue1 = ReturnRewardStrategy.calcPortfolioValue(Exchange.getSimulationMetadata.currentTimeInterval)
      val productValue = portfolioValue1 - Wallets.getWallet(QuoteVolume).balance.toDouble
      Account.placedOrders.keys.foreach(orderId => Account.cancelOrder(new CancellationRequest(orderId)))
      Exchange.step(Constants.emptyStepRequest)
      val portfolioValue2 = ReturnRewardStrategy.calcPortfolioValue(Exchange.getSimulationMetadata.currentTimeInterval)
      val expectedReward = (portfolioValue2 - portfolioValue1)

      assert(expectedReward == ReturnRewardStrategy.calcReward)
      assert(expectedReward == -productValue)
    }
  }
}

object TestReturnRewardStrategy {
  def populateOrderBook: Unit = {
    Account.placeBuyLimitOrder(buyLimitOrderRequest)
    Account.placeSellLimitOrder(sellLimitOrderRequest)
    Exchange.step(Constants.emptyStepRequest)
  }
}

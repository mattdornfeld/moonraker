package co.firstorderlabs.coinbaseml.common.rewards

import co.firstorderlabs.coinbaseml.common.InfoAggregator
import co.firstorderlabs.coinbaseml.fakebase.TestData.RequestsData._
import co.firstorderlabs.coinbaseml.fakebase._
import co.firstorderlabs.common.currency.Configs.ProductPrice
import co.firstorderlabs.common.currency.Configs.ProductPrice.{ProductVolume, QuoteVolume}
import co.firstorderlabs.common.protos.environment.InfoDictKey
import co.firstorderlabs.common.protos.events.OrderSide
import co.firstorderlabs.common.protos.fakebase.{CancellationRequest, SimulationInfoRequest, StepRequest}
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
        ReturnRewardStrategy.currentPortfolioValue == Wallets.getWallet(QuoteVolume).balance.toDouble
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
        expectedMidPrice == ReturnRewardStrategy.calcMidPrice
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
      Exchange.start(simulationStartRequest)
      TestReturnRewardStrategy.populateOrderBook
      val portfolioValue1 = ReturnRewardStrategy.currentPortfolioValue
      val productValue = portfolioValue1 - Wallets.getWallet(QuoteVolume).balance.toDouble
      Account.placedOrders.keys.foreach(orderId => Account.cancelOrder(new CancellationRequest(orderId)))
      Exchange.step(Constants.emptyStepRequest)
      val portfolioValue2 = ReturnRewardStrategy.currentPortfolioValue
      val expectedReward = (portfolioValue2 - portfolioValue1)

      assert(expectedReward == ReturnRewardStrategy.calcReward)
      assert(expectedReward == -productValue)
    }

    it("") {
      Exchange.start(simulationStartRequest)
      TestReturnRewardStrategy.populateOrderBook
      Exchange.checkpoint(Constants.emptyProto)
      val portfolioValue = ReturnRewardStrategy.currentPortfolioValue
      println(portfolioValue)

      val buyOrders = TestUtils
        .generateOrdersForRangeOfPrices(
          new ProductPrice(Right("1.00")),
          new ProductPrice(Right("1002.00")),
          new ProductPrice(Right("1005.00")),
          OrderSide.buy,
          new ProductVolume(Right("1.00")),
          Exchange.getSimulationMetadata.currentTimeInterval.endTime
        )

      Exchange.step(new StepRequest(insertOrders = buyOrders))
      println(ReturnRewardStrategy.currentPortfolioValue)
      Exchange.reset(new SimulationInfoRequest)
      println(ReturnRewardStrategy.currentPortfolioValue)
      println(InfoAggregator.getInfoDict(InfoDictKey.portfolioValue))
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

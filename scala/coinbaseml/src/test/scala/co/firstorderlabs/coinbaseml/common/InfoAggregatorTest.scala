package co.firstorderlabs.coinbaseml.common

import co.firstorderlabs.coinbaseml.common.rewards.ReturnRewardStrategy
import co.firstorderlabs.coinbaseml.common.utils.TestUtils.doubleEquality
import co.firstorderlabs.coinbaseml.common.utils.Utils.getResult
import co.firstorderlabs.coinbaseml.fakebase.TestData.OrdersData
import co.firstorderlabs.coinbaseml.fakebase.TestData.RequestsData._
import co.firstorderlabs.coinbaseml.fakebase._
import co.firstorderlabs.common.currency.Configs.ProductPrice
import co.firstorderlabs.common.protos.environment.InfoDictKey
import co.firstorderlabs.common.protos.fakebase.{BuyLimitOrderRequest, BuyMarketOrderRequest, SellLimitOrderRequest, SellMarketOrderRequest, SimulationInfoRequest, StepRequest}
import co.firstorderlabs.common.types.Events.OrderRequest
import org.scalatest.funspec.AnyFunSpec

class InfoAggregatorTest extends AnyFunSpec {
  Configs.testMode = true

  private def placeOrder(orderRequest: OrderRequest): Unit =
    orderRequest match {
      case buyLimitOrderRequest: BuyLimitOrderRequest =>
        Account.placeBuyLimitOrder(buyLimitOrderRequest)
      case buyMarketOrderRequest: BuyMarketOrderRequest =>
        Account.placeBuyMarketOrder(buyMarketOrderRequest)
      case sellLimitOrderRequest: SellLimitOrderRequest =>
        Account.placeSellLimitOrder(sellLimitOrderRequest)
      case sellMarketOrderRequest: SellMarketOrderRequest =>
        Account.placeSellMarketOrder(sellMarketOrderRequest)
    }
  describe("InfoAggregator") {
    it(
      "InfoAggregator should correctly count the number of orders placed for all order types"
    ) {
      Seq(
        (buyLimitOrderRequest, InfoDictKey.buyOrdersPlaced),
        (buyMarketOrderRequest, InfoDictKey.buyOrdersPlaced),
        (sellLimitOrderRequest, InfoDictKey.sellOrdersPlaced),
        (sellMarketOrderRequest, InfoDictKey.sellOrdersPlaced)
      ).foreach { item =>
        Exchange.start(simulationStartRequest)
        placeOrder(item._1)
        Exchange.step(Constants.emptyStepRequest)
        assert(InfoAggregator.getInfoDict.get(item._2).get === 1.0)
      }
    }

    it("InfoAggregator should correctly count the number of orders rejected") {
      Seq(
        fundsTooLargeOrderRequest,
        fundsTooSmallOrderRequest,
        insufficientFundsOrderRequest,
        postOnlyOrderRequest,
        priceTooLargeOrderRequest,
        priceTooSmallOrderRequest,
        sizeTooLargeOrderRequest,
        sizeTooSmallOrderRequest
      ).foreach { orderRequest =>
        Exchange.start(simulationStartRequest)

        val sellOrders = OrdersData.insertSellOrders(
          minPrice = postOnlyOrderRequest.price,
          numOrders = 2
        )
        Exchange.step(StepRequest(insertOrders = sellOrders))
        placeOrder(orderRequest)
        Exchange.step(Constants.emptyStepRequest)
        assert(
          InfoAggregator.getInfoDict
            .get(InfoDictKey.numOrdersRejected)
            .get === 1.0
        )
      }
    }

    it("InfoAggregator should return the correct portfolio value.") {
      Exchange.start(simulationStartRequestWarmup)

      val buyOrders = OrdersData.insertBuyOrders(
        maxPrice = new ProductPrice(Right("500.00")),
        numOrders = 1
      )

      val sellOrders = OrdersData.insertSellOrders(
        minPrice = new ProductPrice(Right("1000.00")),
        numOrders = 1
      )

      Exchange.step(StepRequest(insertOrders = buyOrders ++ sellOrders))

      val bestBid =
        buyOrders.map(order => order.asMessage.getBuyLimitOrder.price)(0)
      val bestAsk =
        sellOrders.map(order => order.asMessage.getSellLimitOrder.price)(0)
      val midPrice = (bestAsk + bestBid) / Right(2.0)
      val expectedPortfolioValue =
        simulationStartRequest.initialQuoteFunds + midPrice * simulationStartRequest.initialProductFunds

      assert(
        expectedPortfolioValue.toDouble ===
          ReturnRewardStrategy.calcPortfolioValue(
            Exchange.getSimulationMetadata.currentTimeInterval
          )
      )
    }

    it(
      "InfoAggregator should calculate the correct fees paid and volume traded when Account orders are matched."
    ) {
      Seq(
        (
          buyLimitOrderRequest,
          InfoDictKey.sellFeesPaid,
          InfoDictKey.sellVolumeTraded
        ),
        (
          sellLimitOrderRequest,
          InfoDictKey.buyFeesPaid,
          InfoDictKey.buyVolumeTraded
        )
      ).foreach { item =>
        Exchange.start(simulationStartRequest)

        val insertOrders = item._1 match {
          case sellLimitOrderRequest: SellLimitOrderRequest =>
            OrdersData.insertBuyOrders(
              maxPrice = sellLimitOrderRequest.price / Right(0.5),
              numOrders = 2
            )
          case buyLimitOrderRequest: BuyLimitOrderRequest =>
            OrdersData.insertSellOrders(
              minPrice = buyLimitOrderRequest.price / Right(2.0),
              numOrders = 2
            )
        }

        Exchange.step(StepRequest(insertOrders = insertOrders))
        placeOrder(item._1)
        Exchange.step(Constants.emptyStepRequest)
        val matches =
          getResult(Account.getMatches(Constants.emptyProto)).matchEvents
        val expectedFeesPaid = matches.map(_.fee.toDouble).reduce(_ + _)
        val expectedVolumeTraded = matches.map(_.size.toDouble).reduce(_ + _)

        assert(expectedFeesPaid === InfoAggregator.getInfoDict.get(item._2).get)
        assert(
          expectedVolumeTraded === InfoAggregator.getInfoDict.get(item._3).get
        )
      }
    }

    it("InfoAggregator should be cleared when Exchange is reset") {
      Exchange.start(simulationStartRequestWarmup)
      assert(!InfoAggregator.getInfoDict.values.forall(_ === 0.0))
      Exchange.reset(SimulationInfoRequest())
      assert(InfoAggregator.getInfoDict.values.forall(_ === 0.0))
    }
  }
}

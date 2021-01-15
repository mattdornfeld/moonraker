package co.firstorderlabs.coinbaseml.common

import co.firstorderlabs.coinbaseml.common.rewards.ReturnRewardStrategy
import co.firstorderlabs.coinbaseml.common.utils.TestUtils.{buildStepRequest, doubleEquality}
import co.firstorderlabs.coinbaseml.common.utils.Utils.getResult
import co.firstorderlabs.coinbaseml.fakebase.TestData.OrdersData
import co.firstorderlabs.coinbaseml.fakebase.TestData.RequestsData._
import co.firstorderlabs.coinbaseml.fakebase._
import co.firstorderlabs.common.currency.Configs.ProductPrice
import co.firstorderlabs.common.protos.environment.InfoDictKey
import co.firstorderlabs.common.protos.fakebase._
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
        (buyLimitOrderRequest _, InfoDictKey.buyOrdersPlaced),
        (buyMarketOrderRequest _, InfoDictKey.buyOrdersPlaced),
        (sellLimitOrderRequest _, InfoDictKey.sellOrdersPlaced),
        (sellMarketOrderRequest _, InfoDictKey.sellOrdersPlaced)
      ).foreach { item =>
        val simulationId = getResult(Exchange.start(simulationStartRequest)).simulationId.get
        val infoDict = SimulationState.getInfoDictOrFail(simulationId)
        placeOrder(item._1(simulationId))
        Exchange.step(buildStepRequest(simulationId))
        assert(infoDict.get(item._2).get === 1.0)
      }
    }

    it("InfoAggregator should correctly count the number of orders rejected") {
      Seq(
        fundsTooLargeOrderRequest _,
        fundsTooSmallOrderRequest _,
        insufficientFundsOrderRequest _,
        postOnlyOrderRequest _,
        priceTooLargeOrderRequest _,
        priceTooSmallOrderRequest _,
        sizeTooLargeOrderRequest _,
        sizeTooSmallOrderRequest _,
      ).foreach { orderRequest =>
        val simulationId = getResult(Exchange.start(simulationStartRequest)).simulationId.get
        implicit val simulationMetadata = SimulationState.getSimulationMetadataOrFail(simulationId)
        val infoDict = SimulationState.getInfoDictOrFail(simulationId)

        val sellOrders = OrdersData.insertSellOrders(
          minPrice = postOnlyOrderRequest(simulationId).price,
          numOrders = 2
        )
        Exchange.step(simulationId.toStepRequest.update(_.insertOrders := sellOrders))
        placeOrder(orderRequest(simulationId))
        Exchange.step(buildStepRequest(simulationId))
        assert(
          infoDict
            .get(InfoDictKey.numOrdersRejected)
            .get === 1.0
        )
      }
    }

    it("InfoAggregator should return the correct portfolio value.") {
      val simulationId =
        getResult(Exchange.start(simulationStartRequestWarmup)).simulationId.get
      val simulationState = SimulationState.getOrFail(simulationId)
      implicit val matchingEngineState = simulationState.matchingEngineState
      implicit val simulationMetadata = simulationState.simulationMetadata

      val buyOrders = OrdersData.insertBuyOrders(
        maxPrice = new ProductPrice(Right("500.00")),
        numOrders = 1
      )

      val sellOrders = OrdersData.insertSellOrders(
        minPrice = new ProductPrice(Right("1000.00")),
        numOrders = 1
      )

      Exchange.step(simulationId.toStepRequest.update(_.insertOrders := buyOrders ++ sellOrders))

      val bestBid =
        buyOrders.map(order => order.asMessage.getBuyLimitOrder.price)(0)
      val bestAsk =
        sellOrders.map(order => order.asMessage.getSellLimitOrder.price)(0)
      val midPrice = (bestAsk + bestBid) / Right(2.0)
      val expectedPortfolioValue =
        simulationStartRequest.initialQuoteFunds + midPrice * simulationStartRequest.initialProductFunds

      assert(
        expectedPortfolioValue.toDouble === ReturnRewardStrategy.currentPortfolioValue
      )
    }

    it(
      "InfoAggregator should calculate the correct fees paid and volume traded when Account orders are matched."
    ) {
      Seq(
        (
          buyLimitOrderRequest _,
          InfoDictKey.buyFeesPaid,
          InfoDictKey.buyVolumeTraded
        ),
        (
          sellLimitOrderRequest _,
          InfoDictKey.sellFeesPaid,
          InfoDictKey.sellVolumeTraded
        )
      ).foreach { item =>
        val simulationId = getResult(Exchange.start(simulationStartRequest)).simulationId.get
        val simulationState = SimulationState.getOrFail(simulationId)
        val infoDict = SimulationState.getInfoDictOrFail(simulationId)
        implicit val matchingEngineState = simulationState.matchingEngineState
        implicit val simulationMetadata = simulationState.simulationMetadata

        val insertOrders = item._1(simulationId) match {
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

        Exchange.step(simulationId.toStepRequest.update(_.insertOrders := insertOrders))
        placeOrder(item._1(simulationId))
        Exchange.step(buildStepRequest(simulationId))
        val matches =
          getResult(Account.getMatches(simulationId)).matchEvents

        val expectedFeesPaid = matches.map(_.fee.toDouble).reduce(_ + _)
        val expectedVolumeTraded =
          matches.map(_.quoteVolume.toDouble).reduce(_ + _)

        assert(expectedFeesPaid === infoDict.get(item._2).get)
        assert(
          expectedVolumeTraded === infoDict.get(item._3).get
        )
      }
    }

    it("InfoAggregator should be reset when Exchange is reset") {
      val simulationId = getResult(Exchange.start(simulationStartRequestWarmup)).simulationId.get
      val expectedInfoDict = SimulationState.getInfoDictOrFail(simulationId)
      Exchange.step(buildStepRequest(simulationId))
      Exchange.reset(simulationId.toObservationRequest)
      val infoDict = SimulationState.getInfoDictOrFail(simulationId)
      println(expectedInfoDict == infoDict)
    }
  }
}

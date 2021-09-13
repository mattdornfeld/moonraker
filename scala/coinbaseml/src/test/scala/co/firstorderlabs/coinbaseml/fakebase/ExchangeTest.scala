package co.firstorderlabs.coinbaseml.fakebase

import co.firstorderlabs.coinbaseml.common.Configs.testMode

import java.time.Duration
import co.firstorderlabs.coinbaseml.common.utils
import co.firstorderlabs.coinbaseml.common.utils.TestUtils.buildStepRequest
import co.firstorderlabs.coinbaseml.common.utils.Utils.getResult
import co.firstorderlabs.coinbaseml.fakebase.TestData.OrdersData.lowerOrder
import co.firstorderlabs.coinbaseml.fakebase.TestData.RequestsData
import co.firstorderlabs.coinbaseml.fakebase.TestData.RequestsData._
import co.firstorderlabs.coinbaseml.fakebase.utils.OrderUtils
import co.firstorderlabs.common.currency.Configs.ProductPrice
import co.firstorderlabs.common.currency.Configs.ProductPrice.{
  ProductVolume,
  QuoteVolume
}
import co.firstorderlabs.common.protos.environment.{
  ObservationRequest,
  RewardRequest,
  RewardStrategy
}
import co.firstorderlabs.common.protos.events.OrderSide
import co.firstorderlabs.common.protos.fakebase.{Wallets => _, _}
import co.firstorderlabs.common.types.Types.{SimulationId, TimeInterval}
import org.scalatest.funspec.AnyFunSpec

class ExchangeTest extends AnyFunSpec {
  testMode = true

  describe("Exchange") {
    it(
      "When a Exchange simulation is started it should set the simulationMetadata, add funds to the wallets, and set" +
        "the currentTimeInterval. If numWarmUpSteps == 0, then no checkpoint should be created."
    ) {
      val simulationInfo = getResult(Exchange.start(simulationStartRequest))
      val simulationState =
        SimulationState.getOrFail(simulationInfo.simulationId.get)
      val simulationMetadata = simulationState.simulationMetadata
      implicit val walletsState = simulationState.accountState.walletsState
      assert(
        simulationMetadata.startTime
          .compareTo(simulationStartRequest.startTime) == 0
      )
      assert(
        simulationMetadata.endTime
          .compareTo(simulationStartRequest.endTime) == 0
      )
      assert(
        simulationMetadata.timeDelta
          .compareTo(simulationStartRequest.timeDelta.get) == 0
      )
      assert(
        simulationMetadata.timeDelta
          .compareTo(simulationStartRequest.timeDelta.get) == 0
      )
      assert(
        simulationMetadata.numWarmUpSteps
          .compareTo(simulationStartRequest.numWarmUpSteps) == 0
      )
      assert(
        simulationMetadata.initialProductFunds equalTo simulationStartRequest.initialProductFunds
      )
      assert(
        simulationMetadata.initialQuoteFunds equalTo simulationStartRequest.initialQuoteFunds
      )

      val productWallet = Wallets.getWallet(ProductVolume)
      val quoteWallet = Wallets.getWallet(QuoteVolume)

      assert(
        productWallet.balance equalTo simulationStartRequest.initialProductFunds
      )
      assert(
        quoteWallet.balance equalTo simulationStartRequest.initialQuoteFunds
      )

      val expectedTimeInterval =
        TimeInterval(
          simulationStartRequest.startTime
            .minus(simulationMetadata.timeDelta),
          simulationStartRequest.startTime
        )
      assert(
        expectedTimeInterval == simulationMetadata.currentTimeInterval
      )

      assert(
        SimulationState.getSnapshot(simulationInfo.simulationId.get).isEmpty
      )
    }

    it(
      "The getOrderBooks endpoint should return empty maps if no orders are on the order book."
    ) {
      val simulationId =
        getResult(Exchange.start(simulationStartRequest)).simulationId.get
      val orderBooks =
        utils.Utils.getResult(
          Exchange.getOrderBooks(orderBooksRequest(simulationId))
        )
      assert(
        orderBooks.buyOrderBook.isEmpty && orderBooks.sellOrderBook.isEmpty
      )
    }

    it(
      "The getOrderBooks endpoint should return maps that aggregate the volume for all orders for each price."
    ) {
      val simulationId =
        getResult(Exchange.start(simulationStartRequest)).simulationId.get
      Account.placeBuyLimitOrder(buyLimitOrderRequest(simulationId))
      Account.placeSellLimitOrder(sellLimitOrderRequest(simulationId))
      Exchange.step(buildStepRequest(simulationId))
      val orderBooks =
        utils.Utils.getResult(
          Exchange.getOrderBooks(orderBooksRequest(simulationId))
        )

      assert(orderBooks.buyOrderBook.size == 1)
      assert(orderBooks.sellOrderBook.size == 1)
      assert(
        orderBooks.buyOrderBook
          .get(buyLimitOrderRequest(simulationId).price)
          .get == buyLimitOrderRequest(simulationId).size
      )
      assert(
        orderBooks.sellOrderBook
          .get(sellLimitOrderRequest(simulationId).price)
          .get == sellLimitOrderRequest(simulationId).size
      )

      val buyOrder =
        utils.Utils.getResult(
          Account.placeBuyLimitOrder(buyLimitOrderRequest(simulationId))
        )
      val sellOrder =
        utils.Utils.getResult(
          Account.placeSellLimitOrder(sellLimitOrderRequest(simulationId))
        )
      Exchange.step(buildStepRequest(simulationId))
      val orderBooks2 =
        utils.Utils.getResult(
          Exchange.getOrderBooks(orderBooksRequest(simulationId))
        )

      assert(orderBooks2.buyOrderBook.size == 1)
      assert(orderBooks2.sellOrderBook.size == 1)
      assert(
        orderBooks2.buyOrderBook
          .get(buyLimitOrderRequest(simulationId).price)
          .get == (buyLimitOrderRequest(simulationId).size * Right(2.0))
      )
      assert(
        orderBooks2.sellOrderBook
          .get(sellLimitOrderRequest(simulationId).price)
          .get == (sellLimitOrderRequest(simulationId).size * Right(2.0))
      )

      List(buyOrder.orderId, sellOrder.orderId)
        .foreach(orderId =>
          Account.cancelOrder(
            new CancellationRequest(orderId, Some(simulationId))
          )
        )
      Exchange.step(buildStepRequest(simulationId))
      val orderBooks3 =
        utils.Utils.getResult(
          Exchange.getOrderBooks(orderBooksRequest(simulationId))
        )

      assert(orderBooks3.buyOrderBook.size == 1)
      assert(orderBooks3.sellOrderBook.size == 1)
      assert(
        orderBooks3.buyOrderBook
          .get(buyLimitOrderRequest(simulationId).price)
          .get == buyLimitOrderRequest(simulationId).size
      )
      assert(
        orderBooks3.sellOrderBook
          .get(sellLimitOrderRequest(simulationId).price)
          .get == sellLimitOrderRequest(simulationId).size
      )
    }

    it(
      "The getOrderBooks endpoint shouldn't return a map bigger than the specified orderDepth."
        + "The max/min price of the returned sellOrderBook/buyOrderBook should be orderBookDepth price ticks away from the best ask/bid."
        + "The min/max price of the returned sellOrderBook/buyOrderBook should be the best ask/bid price."
    ) {
      val simulationId =
        getResult(Exchange.start(simulationStartRequest)).simulationId.get
      val simulationMetadata =
        SimulationState.getSimulationMetadataOrFail(simulationId)
      val productVolume = new ProductVolume(Right("1.00"))
      val buyOrders = TestUtils
        .generateOrdersForRangeOfPrices(
          new ProductPrice(Right("1.00")),
          new ProductPrice(Right("900.00")),
          new ProductPrice(Right("915.00")),
          OrderSide.buy,
          productVolume,
          simulationMetadata.currentTimeInterval.endTime
        )

      val sellOrders = TestUtils
        .generateOrdersForRangeOfPrices(
          new ProductPrice(Right("1.00")),
          new ProductPrice(Right("1000.00")),
          new ProductPrice(Right("1015.00")),
          OrderSide.sell,
          productVolume,
          simulationMetadata.currentTimeInterval.endTime
        )

      Exchange.step(
        simulationId.toStepRequest
          .update(_.insertOrders := buyOrders ++ sellOrders)
      )

      val orderBooks =
        utils.Utils.getResult(
          Exchange.getOrderBooks(orderBooksRequest(simulationId))
        )
      List(orderBooks.buyOrderBook, orderBooks.sellOrderBook)
        .foreach(orderBook => assert(orderBook.size == 10))

      List(orderBooks.buyOrderBook, orderBooks.sellOrderBook)
        .foreach(orderBook =>
          orderBook.values
            .foreach(volume => assert(volume equalTo productVolume))
        )

      assert(
        orderBooks.buyOrderBook.min._1 equalTo buyOrders(
          buyOrders.size - orderBooksRequest(simulationId).orderBookDepth
        ).asMessage.getBuyLimitOrder.price
      )
      assert(
        orderBooks.buyOrderBook.max._1 equalTo buyOrders.last.asMessage.getBuyLimitOrder.price
      )
      assert(
        orderBooks.sellOrderBook.min._1 equalTo sellOrders.head.asMessage.getSellLimitOrder.price
      )
      assert(
        orderBooks.sellOrderBook.max._1 equalTo sellOrders(
          orderBooksRequest(simulationId).orderBookDepth - 1
        ).asMessage.getSellLimitOrder.price
      )
    }

    it("Expired orders should be cancelled when step is called") {
      val timeToLive = simulationStartRequest.timeDelta
        .map(duration => Duration.ofSeconds((duration.toSeconds * 1.5).toLong))

      def buyLimitOrderRequest(simulationId: SimulationId) =
        new BuyLimitOrderRequest(
          new ProductPrice(Right("200.00")),
          ProductPrice.productId,
          new ProductVolume(Right("10.000000")),
          false,
          timeToLive,
          simulationId = Some(simulationId)
        )

      def sellLimitOrderRequest(simulationId: SimulationId) =
        new SellLimitOrderRequest(
          new ProductPrice(Right("50.00")),
          ProductPrice.productId,
          new ProductVolume(Right("10.000000")),
          false,
          timeToLive,
          simulationId = Some(simulationId)
        )

      List(buyLimitOrderRequest _, sellLimitOrderRequest _).foreach {
        orderRequest =>
          val simulationId =
            getResult(Exchange.start(simulationStartRequest)).simulationId.get
          val accountState = SimulationState.getAccountStateOrFail(simulationId)

          val orderFuture = orderRequest(simulationId) match {
            case orderRequest: BuyLimitOrderRequest =>
              Account.placeBuyLimitOrder(orderRequest)
            case orderRequest: SellLimitOrderRequest =>
              Account.placeSellLimitOrder(orderRequest)
          }

          val order = utils.Utils.getResult(orderFuture)

          Exchange.step(buildStepRequest(simulationId))

          assert(
            accountState.placedOrders.get(order.orderId).get.orderStatus.isopen
          )

          val stepRequest = buildStepRequest(simulationId)
          List.range(1, 3).foreach { _ =>
            Exchange.step(stepRequest)
          }

          val cancelledOrder = accountState.placedOrders.get(order.orderId).get
          assert(cancelledOrder.orderStatus.isdone)
          assert(cancelledOrder.doneReason.iscanceled)
          assert(
            Duration
              .between(cancelledOrder.time, cancelledOrder.doneAt)
              .compareTo(timeToLive.get) >= 0
          )
      }
    }

    it("Ensure getSimulationInfo returns Observation.") {
      val simulationId =
        getResult(Exchange.start(simulationStartRequestWarmup)).simulationId.get

      val observationRequest =
        RequestsData.observationRequest
          .withRewardRequest(
            RewardRequest(RewardStrategy.LogReturnRewardStrategy)
          )
          .withSimulationId(simulationId)

      val simulationInfo =
        Exchange.getSimulationInfo(observationRequest)
      assert(simulationInfo.observation.isDefined)
    }

    it(
      "This test is to ensure that the order book is cloned and restored properly during a checkpoint."
    ) {
      val simulationId =
        getResult(Exchange.start(simulationStartRequestWarmup)).simulationId.get
      val simulationState = SimulationState.getOrFail(simulationId)
      val matchingEngineState = simulationState.matchingEngineState
      val simulationMetadata = simulationState.simulationMetadata
      Exchange.step(
        simulationId.toStepRequest.update(_.insertOrders := List(lowerOrder))
      )
      Exchange.checkpoint(simulationId)
      Exchange.step(
        simulationId.toStepRequest.update(
          _.insertCancellations := List(
            OrderUtils.cancellationFromOrder(lowerOrder)(simulationMetadata)
          )
        )
      )

      // Assert order is not on order book
      val orderBookState =
        matchingEngineState.getOrderBookState(lowerOrder.side)
      assert(
        OrderBook.getOrderByOrderId(lowerOrder.orderId)(orderBookState).isEmpty
      )
      assert(
        OrderBook
          .getOrderByOrderBookKey(lowerOrder.orderBookKey)(orderBookState)
          .isEmpty
      )

      Exchange.reset(simulationId.toObservationRequest)
      val restoredSimulationState = SimulationState.getOrFail(simulationId)
      val restoredOrderBookState = restoredSimulationState.matchingEngineState
        .getOrderBookState(lowerOrder.side)
      val restoredSimulationMetadata =
        restoredSimulationState.simulationMetadata

      // Assert order has been re-added to order book
      assert(
        OrderBook
          .getOrderByOrderId(lowerOrder.orderId)(restoredOrderBookState)
          .nonEmpty
      )
      assert(
        OrderBook
          .getOrderByOrderBookKey(lowerOrder.orderBookKey)(
            restoredOrderBookState
          )
          .nonEmpty
      )

      Exchange.step(
        simulationId.toStepRequest.update(
          _.insertCancellations := List(
            OrderUtils.cancellationFromOrder(lowerOrder)(
              restoredSimulationMetadata
            )
          )
        )
      )

      // Assert order successfully re-removed from order book
      assert(
        OrderBook
          .getOrderByOrderId(lowerOrder.orderId)(restoredOrderBookState)
          .isEmpty
      )
      assert(
        OrderBook
          .getOrderByOrderBookKey(lowerOrder.orderBookKey)(
            restoredOrderBookState
          )
          .isEmpty
      )

      Exchange.reset(simulationId.toObservationRequest)
      val restoredSimulationState2 =
        SimulationState.getSimulationMetadataOrFail(simulationId)

      Exchange.step(
        simulationId.toStepRequest.update(
          _.insertCancellations := List(
            OrderUtils
              .cancellationFromOrder(lowerOrder)(restoredSimulationState2)
          )
        )
      )
    }
  }
}

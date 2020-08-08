package co.firstorderlabs.fakebase

import java.time.Duration

import co.firstorderlabs.fakebase.TestData.OrdersData
import co.firstorderlabs.fakebase.TestData.RequestsData._
import co.firstorderlabs.fakebase.currency.Configs.ProductPrice
import co.firstorderlabs.fakebase.currency.Configs.ProductPrice.{
  ProductVolume,
  QuoteVolume
}
import co.firstorderlabs.fakebase.protos.fakebase.{
  BuyLimitOrderRequest,
  CancellationRequest,
  ExchangeInfoRequest,
  OrderSide,
  SellLimitOrderRequest,
  StepRequest
}
import co.firstorderlabs.fakebase.types.Types.TimeInterval
import org.scalatest.funspec.AnyFunSpec

class ExchangeTest extends AnyFunSpec {
  Configs.testMode = true

  describe("Exchange") {
    it(
      "When a Exchange simulation is started it should set the simulationMetadata, add funds to the wallets, and set" +
        "the currentTimeInterval. If numWarmUpSteps == 0, then no checkpoint should be created."
    ) {
      Exchange.start(simulationStartRequest)
      val simulationMetadata = Exchange.getSimulationMetadata
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
        expectedTimeInterval == Exchange.getSimulationMetadata.currentTimeInterval
      )

      assert(simulationMetadata.checkpoint.isEmpty)
    }

    it(
      "If simulationStartRequest.numWarmUpSteps > 0, then a checkpoint should be created. After the simulation advances," +
        "when reset, it should return to that checkpoint."
    ) {
      Exchange.start(checkpointedSimulationStartRequest)
      val simulationMetadata = Exchange.getSimulationMetadata

      assert(simulationMetadata.checkpoint.isDefined)

      (1 to 5) foreach (_ => Exchange.step(Constants.emptyStepRequest))

      Exchange.reset(ExchangeInfoRequest())

      assert(simulationMetadata.checkpoint.get == Checkpointer.createCheckpoint)
    }

    it(
      "No matter what orders are placed on the order book after a checkpoint is created, the simulation state should" +
        "return to the checkpoint state when reset is called."
    ) {
      Exchange.start(simulationStartRequest)
      advanceExchange
      Exchange.reset(ExchangeInfoRequest())

      assert(
        Exchange.getSimulationMetadata.checkpoint.get == Checkpointer.createCheckpoint
      )
    }

    it(
      "If a simulation is started then stopped, its state should be cleared completely and DatabaseWorkers should enter a paused state."
    ) {
      Exchange.start(simulationStartRequest)
      advanceExchange
      Exchange.stop(Constants.emptyProto)
      assert(Checkpointer.isCleared)
      assert(DatabaseWorkers.isPaused)
    }

    it(
      "The getOrderBooks endpoint should return empty maps if no orders are on the order book."
    ) {
      Exchange.start(simulationStartRequest)
      val orderBooks =
        Utils.getResult(Exchange.getOrderBooks(orderBooksRequest))
      assert(
        orderBooks.buyOrderBook.isEmpty && orderBooks.sellOrderBook.isEmpty
      )
    }

    it(
      "The getOrderBooks endpoint should return maps that aggregate the volume for all orders for each price."
    ) {
      Exchange.start(simulationStartRequest)
      Account.placeBuyLimitOrder(buyLimitOrderRequest)
      Account.placeSellLimitOrder(sellLimitOrderRequest)
      Exchange.step(Constants.emptyStepRequest)
      val orderBooks =
        Utils.getResult(Exchange.getOrderBooks(orderBooksRequest))

      assert(orderBooks.buyOrderBook.size == 1)
      assert(orderBooks.sellOrderBook.size == 1)
      assert(
        orderBooks.buyOrderBook
          .get(buyLimitOrderRequest.price.toPlainString)
          .get == buyLimitOrderRequest.size.toPlainString
      )
      assert(
        orderBooks.sellOrderBook
          .get(sellLimitOrderRequest.price.toPlainString)
          .get == sellLimitOrderRequest.size.toPlainString
      )

      val buyOrder =
        Utils.getResult(Account.placeBuyLimitOrder(buyLimitOrderRequest))
      val sellOrder =
        Utils.getResult(Account.placeSellLimitOrder(sellLimitOrderRequest))
      Exchange.step(Constants.emptyStepRequest)
      val orderBooks2 =
        Utils.getResult(Exchange.getOrderBooks(orderBooksRequest))

      assert(orderBooks2.buyOrderBook.size == 1)
      assert(orderBooks2.sellOrderBook.size == 1)
      assert(
        orderBooks2.buyOrderBook
          .get(buyLimitOrderRequest.price.toPlainString)
          .get == (buyLimitOrderRequest.size * Right(2.0)).toPlainString
      )
      assert(
        orderBooks2.sellOrderBook
          .get(sellLimitOrderRequest.price.toPlainString)
          .get == (sellLimitOrderRequest.size * Right(2.0)).toPlainString
      )

      List(buyOrder.orderId, sellOrder.orderId)
        .foreach(
          orderId => Account.cancelOrder(new CancellationRequest(orderId))
        )
      Exchange.step(Constants.emptyStepRequest)
      val orderBooks3 =
        Utils.getResult(Exchange.getOrderBooks(orderBooksRequest))

      assert(orderBooks3.buyOrderBook.size == 1)
      assert(orderBooks3.sellOrderBook.size == 1)
      assert(
        orderBooks3.buyOrderBook
          .get(buyLimitOrderRequest.price.toPlainString)
          .get == buyLimitOrderRequest.size.toPlainString
      )
      assert(
        orderBooks3.sellOrderBook
          .get(sellLimitOrderRequest.price.toPlainString)
          .get == sellLimitOrderRequest.size.toPlainString
      )
    }

    it(
      "The getOrderBooks endpoint shouldn't return a map bigger than the specified orderDepth."
        + "The max/min price of the returned sellOrderBook/buyOrderBook should be orderBookDepth price ticks away from the best ask/bid."
        + "The min/max price of the returned sellOrderBook/buyOrderBook should be the best ask/bid price."
    ) {
      Exchange.start(simulationStartRequest)
      val productVolume = new ProductVolume(Right("1.00"))
      val buyOrders = TestUtils
        .generateOrdersForRangeOfPrices(
          new ProductPrice(Right("1.00")),
          new ProductPrice(Right("900.00")),
          new ProductPrice(Right("915.00")),
          OrderSide.buy,
          productVolume,
          Exchange.getSimulationMetadata.currentTimeInterval.endTime
        )

      val sellOrders = TestUtils
        .generateOrdersForRangeOfPrices(
          new ProductPrice(Right("1.00")),
          new ProductPrice(Right("1000.00")),
          new ProductPrice(Right("1015.00")),
          OrderSide.sell,
          productVolume,
          Exchange.getSimulationMetadata.currentTimeInterval.endTime
        )

      Exchange.step(new StepRequest(insertOrders = buyOrders ++ sellOrders))

      val orderBooks =
        Utils.getResult(Exchange.getOrderBooks(orderBooksRequest))
      List(orderBooks.buyOrderBook, orderBooks.sellOrderBook)
        .foreach(orderBook => assert(orderBook.size == 10))

      List(orderBooks.buyOrderBook, orderBooks.sellOrderBook)
        .foreach(
          orderBook =>
            orderBook.values
              .foreach(volume => assert(volume == productVolume.toPlainString))
        )

      assert(
        orderBooks.buyOrderBook.min._1 == buyOrders(
          buyOrders.size - orderBooksRequest.orderBookDepth
        ).asMessage.getBuyLimitOrder.price.toPlainString
      )
      assert(
        orderBooks.buyOrderBook.max._1 == buyOrders.last.asMessage.getBuyLimitOrder.price.toPlainString
      )
      assert(
        orderBooks.sellOrderBook.min._1 == sellOrders.head.asMessage.getSellLimitOrder.price.toPlainString
      )
      assert(
        orderBooks.sellOrderBook.max._1 == sellOrders(
          orderBooksRequest.orderBookDepth - 1
        ).asMessage.getSellLimitOrder.price.toPlainString
      )
    }

    it("Expired orders should be cancelled when step is called") {
      val timeToLive = simulationStartRequest.timeDelta
        .map(duration => Duration.ofSeconds((duration.toSeconds * 1.5).toLong))

      val buyLimitOrderRequest = new BuyLimitOrderRequest(
        new ProductPrice(Right("200.00")),
        ProductPrice.productId,
        new ProductVolume(Right("10.000000")),
        false,
        timeToLive,
      )

      val sellLimitOrderRequest = new SellLimitOrderRequest(
        new ProductPrice(Right("50.00")),
        ProductPrice.productId,
        new ProductVolume(Right("10.000000")),
        false,
        timeToLive,
      )

      List(buyLimitOrderRequest, sellLimitOrderRequest).foreach {
        orderRequest =>
          Exchange.start(simulationStartRequest)

          val orderFuture = orderRequest match {
            case orderRequest: BuyLimitOrderRequest =>
              Account.placeBuyLimitOrder(orderRequest)
            case orderRequest: SellLimitOrderRequest =>
              Account.placeSellLimitOrder(orderRequest)
          }

          val order = Utils.getResult(orderFuture)

          Exchange.step(Constants.emptyStepRequest)

          assert(Account.placedOrders.get(order.orderId).get.orderStatus.isopen)

          List.range(1, 3).foreach { _ =>
            Exchange.step(Constants.emptyStepRequest)
          }

          val cancelledOrder = Account.placedOrders.get(order.orderId).get
          assert(cancelledOrder.orderStatus.isdone)
          assert(cancelledOrder.doneReason.iscancelled)
          assert(
            Duration
              .between(cancelledOrder.time, cancelledOrder.doneAt)
              .compareTo(timeToLive.get) >= 0
          )
      }
    }

    def advanceExchange: Unit = {
      Exchange step StepRequest(
        insertOrders = OrdersData.insertSellOrders(
          new ProductPrice(Right("100.00")),
          new ProductVolume(Right("0.5"))
        ) ++ OrdersData.insertBuyOrders(new ProductPrice(Right("100.00")))
      )

      Account.placeBuyMarketOrder(buyMarketOrderRequest)

      Exchange.checkpoint(Constants.emptyProto)

      Exchange step StepRequest(
        insertOrders = OrdersData.insertSellOrders(
          new ProductPrice(Right("100.00")),
          new ProductVolume(Right("0.5"))
        ) ++ OrdersData.insertBuyOrders(new ProductPrice(Right("100.00")))
      )

      Account.placeBuyMarketOrder(buyMarketOrderRequest)
    }
  }
}

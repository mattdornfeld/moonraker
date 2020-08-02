package co.firstorderlabs.fakebase

import java.time.Duration

import co.firstorderlabs.fakebase.TestData.OrdersData
import co.firstorderlabs.fakebase.TestData.RequestsData._
import co.firstorderlabs.fakebase.currency.Configs.ProductPrice
import co.firstorderlabs.fakebase.currency.Configs.ProductPrice.{ProductVolume, QuoteVolume}
import co.firstorderlabs.fakebase.protos.fakebase.{CancellationRequest, ExchangeInfoRequest, OrderSide, StepRequest}
import co.firstorderlabs.fakebase.types.Types.{Datetime, TimeInterval}
import org.scalatest.funspec.AnyFunSpec

class ExchangeTest extends AnyFunSpec {
  Configs.testMode = true

  describe("Exchange") {
    it(
      "When a Exchange simulation is started it should set the simulationMetadata, add funds to the wallets, and set" +
        "the currentTimeInterval. If numWarmUpSteps == 0, then no checkpoint should be created."
    ) {
      Exchange.start(simulationStartRequest)
      val simulationMetadata = Exchange.simulationMetadata.get
      assert(
        simulationMetadata.startTime.instant
          .compareTo(simulationStartRequest.startTime.instant) == 0
      )
      assert(
        simulationMetadata.endTime.instant
          .compareTo(simulationStartRequest.endTime.instant) == 0
      )
      assert(
        simulationMetadata.timeDelta.compareTo(
          Duration ofSeconds simulationStartRequest.timeDelta.get.seconds
        ) == 0
      )
      assert(
        simulationMetadata.timeDelta.compareTo(
          Duration ofSeconds simulationStartRequest.timeDelta.get.seconds
        ) == 0
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
          Datetime(
            simulationStartRequest.startTime.instant
              .minus(simulationMetadata.timeDelta)
          ),
          simulationStartRequest.startTime
        )
      assert(
        expectedTimeInterval == Exchange.simulationMetadata.get.currentTimeInterval
      )

      assert(simulationMetadata.checkpoint.isEmpty)
    }

    it(
      "If simulationStartRequest.numWarmUpSteps > 0, then a checkpoint should be created. After the simulation advances," +
        "when reset, it should return to that checkpoint."
    ) {
      Exchange.start(checkpointedSimulationStartRequest)
      val simulationMetadata = Exchange.simulationMetadata.get

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
        Exchange.simulationMetadata.get.checkpoint.get == Checkpointer.createCheckpoint
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
          Exchange.simulationMetadata.get.currentTimeInterval.endTime
        )

      val sellOrders = TestUtils
        .generateOrdersForRangeOfPrices(
          new ProductPrice(Right("1.00")),
          new ProductPrice(Right("1000.00")),
          new ProductPrice(Right("1015.00")),
          OrderSide.sell,
          productVolume,
          Exchange.simulationMetadata.get.currentTimeInterval.endTime
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

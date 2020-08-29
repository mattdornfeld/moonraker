package co.firstorderlabs.common.featurizers

import co.firstorderlabs.common.protos.featurizer.ObservationRequest
import co.firstorderlabs.fakebase.TestData.RequestsData._
import co.firstorderlabs.fakebase.currency.Configs.ProductPrice
import co.firstorderlabs.fakebase.currency.Price.BtcUsdPrice.ProductVolume
import co.firstorderlabs.fakebase.protos.fakebase.{Order, OrderSide, StepRequest}
import co.firstorderlabs.fakebase._
import org.scalactic.TolerantNumerics
import org.scalatest.funspec.AnyFunSpec

class TestOrderBookFeaturizer extends AnyFunSpec {
  Configs.testMode = true
  val productVolume = new ProductVolume(Right("1.00"))
  val zerosArray =
    OrderBookFeaturizer.getArrayOfZeros(orderBooksRequest.orderBookDepth, 4)
  implicit val doubleEquality = TolerantNumerics.tolerantDoubleEquality(1e-10)

  def placeOrders: (List[Order], List[Order]) = {
    val buyOrders = TestUtils
      .generateOrdersForRangeOfPrices(
        new ProductPrice(Right("1.00")),
        new ProductPrice(Right("900.00")),
        new ProductPrice(Right("902.00")),
        OrderSide.buy,
        productVolume,
        Exchange.getSimulationMetadata.currentTimeInterval.endTime
      )

    val sellOrders = TestUtils
      .generateOrdersForRangeOfPrices(
        new ProductPrice(Right("1.00")),
        new ProductPrice(Right("1000.00")),
        new ProductPrice(Right("1002.00")),
        OrderSide.sell,
        productVolume,
        Exchange.getSimulationMetadata.currentTimeInterval.endTime
      )

    (buyOrders, sellOrders)
  }

  describe("OrderBookFeaturizer") {
    it(
      "When orders are placed on the order book, the array returned from OrderBookFeaturizer.getBestBidsAsksArray " +
        "should be the 0th element of the array returned from OrderBookFeaturizer.getBestBidsAsksArrayOverTime. The" +
        "other elements of that array should be 2d sub arrays with all zeros as entries. As the simulation is stepped" +
        "forward the array returned from OrderBookFeaturizer.getBestBidsAsksArray should be both the 0th and 1st element" +
        "of the array returned from OrderBookFeaturizer.getBestBidsAsksArrayOverTime. This is because the order book is not" +
        "changing so the features associated with the order book are not changing."
    ) {
      Exchange.start(simulationStartRequest)

      val (buyOrders, sellOrders) = placeOrders

      Exchange.step(new StepRequest(insertOrders = buyOrders ++ sellOrders))

      val bestBidsAsksOverTime =
        OrderBookFeaturizer.getBestBidsAsksArrayOverTime(
          orderBooksRequest.orderBookDepth
        )
      val bestBidsAsks = OrderBookFeaturizer.getBestBidsAsksArray(
        SnapshotBuffer.getSnapshot(
          Exchange.getSimulationMetadata.currentTimeInterval
        ),
        orderBooksRequest.orderBookDepth
      )

      assert(!(bestBidsAsks sameElements zerosArray))
      assert(bestBidsAsks sameElements bestBidsAsksOverTime(0))
      assert(zerosArray sameElements bestBidsAsksOverTime(1))
      assert(zerosArray sameElements bestBidsAsksOverTime(2))

      Exchange.step(Constants.emptyStepRequest)

      val bestBidsAsksOverTime2 =
        OrderBookFeaturizer.getBestBidsAsksArrayOverTime(
          orderBooksRequest.orderBookDepth
        )

      assert(bestBidsAsks sameElements bestBidsAsksOverTime2(0))
      assert(bestBidsAsks sameElements bestBidsAsksOverTime2(1))
      assert(zerosArray sameElements bestBidsAsksOverTime2(2))
    }

    it(
      "When orders are placed on the order book then cancelled in the next simulation step, the 0th element of the array" +
        "returned from OrderBookFeaturizer.getBestBidsAsksArrayOverTime should be a 2D array with all zeros as entries."
    ) {
      Exchange.start(simulationStartRequest)

      val (buyOrders, sellOrders) = placeOrders

      Exchange.step(new StepRequest(insertOrders = buyOrders ++ sellOrders))

      val bestBidsAsks = OrderBookFeaturizer.getBestBidsAsksArray(
        SnapshotBuffer.getSnapshot(
          Exchange.getSimulationMetadata.currentTimeInterval
        ),
        orderBooksRequest.orderBookDepth
      )

      List(OrderSide.buy, OrderSide.sell).foreach { orderSide =>
        Exchange
          .getOrderBook(orderSide)
          .iterator
          .foreach(item => Exchange.cancelOrder(item._2))
      }

      Exchange.step(Constants.emptyStepRequest)

      val bestBidsAsksOverTime =
        OrderBookFeaturizer.getBestBidsAsksArrayOverTime(
          orderBooksRequest.orderBookDepth
        )

      assert(zerosArray sameElements bestBidsAsksOverTime(0))
      assert(bestBidsAsks sameElements bestBidsAsksOverTime(1))
      assert(zerosArray sameElements bestBidsAsksOverTime(2))
    }

    it(
      "The array returned from OrderBookFeaturizer.getBestBidsAsksArray contain as rows 1D arrays of" +
        "the form (price_ask, volume_ask, price_bid, volume_bid, where the prices and volumes are the bid" +
        "and ask prices on the buy and sell order books. The rows in the array should be ordered in descending order," +
        "where the best bids and asks are in the top rows."
    ) {
      Exchange.start(simulationStartRequest)
      val (buyOrders, sellOrders) = placeOrders

      Exchange.step(new StepRequest(insertOrders = buyOrders ++ sellOrders))

      val bestBidsAsksArray = OrderBookFeaturizer.getBestBidsAsksArray(
        SnapshotBuffer.mostRecentSnapshot,
        orderBooksRequest.orderBookDepth
      )

      val expectedSellFeatures = sellOrders
        .map(
          order =>
            List(
              order.asMessage.getSellLimitOrder.price.toDouble,
              productVolume.toDouble
          )
        )
        .flatten

      val expectedBuyFeatures =
        buyOrders
          .map(
            order =>
              List(
                order.asMessage.getBuyLimitOrder.price.toDouble,
                productVolume.toDouble
            )
          )
          .flatten

      assert(
        bestBidsAsksArray(0).slice(0, 2) sameElements expectedSellFeatures
          .slice(0, 2)
      )
      assert(
        bestBidsAsksArray(0).slice(2, 4) sameElements expectedBuyFeatures
          .slice(2, 4)
      )
      assert(
        bestBidsAsksArray(1).slice(0, 2) sameElements expectedSellFeatures
          .slice(2, 4)
      )
      assert(
        bestBidsAsksArray(1).slice(2, 4) sameElements expectedBuyFeatures
          .slice(0, 2)
      )
    }

    it(
      "When two orders are placed on the order book at the same price in separate time steps, the volume component of the" +
        "array should be the sum of the sizes of the orders."
    ) {
      Exchange.start(simulationStartRequest)
      Range(0, 2).foreach { _ =>
        Account.placeBuyLimitOrder(buyLimitOrderRequest)
        Exchange.step(Constants.emptyStepRequest)
      }
      val bestBidsAsksArray = OrderBookFeaturizer.getBestBidsAsksArray(
        SnapshotBuffer.mostRecentSnapshot,
        orderBooksRequest.orderBookDepth
      )

      bestBidsAsksArray(0)(2) === buyLimitOrderRequest.price.toDouble
      bestBidsAsksArray(0)(3) === 2.0 * buyLimitOrderRequest.size.toDouble
    }

    it(
      "The OrderBookFeaturizer should return an array of length 4 * orderBookDepth * SnapshotBuffer.getMaxSize." +
        "The first elements of the array should be equal to the elements returned by OrderBookFeaturizer.getBestBidsAsksArray." +
        "The latter elements should be all 0.0 "
    ) {
      Exchange.start(simulationStartRequest)
      val productVolume = new ProductVolume(Right("1.00"))
      val (buyOrders, sellOrders) = placeOrders

      Exchange.step(new StepRequest(insertOrders = buyOrders ++ sellOrders))

      val orderBookFeatures =
        OrderBookFeaturizer.construct(new ObservationRequest(10))
      val bestBidsAsksArray = OrderBookFeaturizer.getBestBidsAsksArray(
        SnapshotBuffer.mostRecentSnapshot,
        orderBooksRequest.orderBookDepth
      )

      assert(
        4 * orderBooksRequest.orderBookDepth * SnapshotBuffer.getMaxSize == orderBookFeatures.size
      )
      assert(
        bestBidsAsksArray.flatten sameElements orderBookFeatures
          .slice(0, 4 * orderBooksRequest.orderBookDepth)
      )
      assert(
        orderBookFeatures
          .drop(4 * orderBooksRequest.orderBookDepth)
          .forall(element => element === 0.0)
      )
    }
  }
}

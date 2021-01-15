package co.firstorderlabs.coinbaseml.common.featurizers

import co.firstorderlabs.coinbaseml.common.utils.TestUtils.{buildStepRequest, doubleEquality}
import co.firstorderlabs.coinbaseml.common.utils.Utils.getResult
import co.firstorderlabs.coinbaseml.fakebase.TestData.RequestsData._
import co.firstorderlabs.coinbaseml.fakebase.{TestUtils, _}
import co.firstorderlabs.common.currency.Configs.ProductPrice
import co.firstorderlabs.common.currency.Configs.ProductPrice.ProductVolume
import co.firstorderlabs.common.protos.environment.ObservationRequest
import co.firstorderlabs.common.protos.events.{Order, OrderSide}
import co.firstorderlabs.common.types.Types.SimulationId
import org.scalatest.funspec.AnyFunSpec

class TestOrderBookFeaturizer extends AnyFunSpec {
  Configs.testMode = true
  val productVolume = new ProductVolume(Right("1.00"))
  val zerosArray =
    OrderBookFeaturizer.getArrayOfZeros(orderBooksRequest(SimulationId("test")).orderBookDepth, 4)

  def placeOrders(
      maxBuyPrice: ProductPrice = new ProductPrice(Right("902.00")),
      maxSellPrice: ProductPrice = new ProductPrice(Right("1002.00")),
  )(implicit simulationMetadata: SimulationMetadata): (List[Order], List[Order]) = {
    val buyOrders = TestUtils
      .generateOrdersForRangeOfPrices(
        new ProductPrice(Right("1.00")),
        new ProductPrice(Right("900.00")),
        maxBuyPrice,
        OrderSide.buy,
        productVolume,
        simulationMetadata.currentTimeInterval.endTime
      )

    val sellOrders = TestUtils
      .generateOrdersForRangeOfPrices(
        new ProductPrice(Right("1.00")),
        new ProductPrice(Right("1000.00")),
        maxSellPrice,
        OrderSide.sell,
        productVolume,
        simulationMetadata.currentTimeInterval.endTime
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
      val simulationId = getResult(Exchange.start(simulationStartRequest)).simulationId.get
      val simulationState = SimulationState.getOrFail(simulationId)
      implicit val simulationMetadata = simulationState.simulationMetadata
      implicit val matchingEngineState = simulationState.matchingEngineState
      implicit val orderBookFeaturizerState = simulationState.environmentState.orderBookFeaturizerState

      val (buyOrders, sellOrders) = placeOrders()

      Exchange.step(simulationId.toStepRequest.update(_.insertOrders := buyOrders ++ sellOrders))

      val bestBidsAsksOverTime =
        OrderBookFeaturizer.getBestBidsAsksArrayOverTime(
          orderBooksRequest(simulationId).orderBookDepth
        )
      val bestBidsAsks = OrderBookFeaturizer.getBestBidsAsksArray(orderBooksRequest(simulationId).orderBookDepth)

      assert(!(bestBidsAsks sameElements zerosArray))
      assert(bestBidsAsks sameElements bestBidsAsksOverTime(0))
      assert(zerosArray sameElements bestBidsAsksOverTime(1))
      assert(zerosArray sameElements bestBidsAsksOverTime(2))

      Exchange.step(buildStepRequest(simulationId))

      val bestBidsAsksOverTime2 =
        OrderBookFeaturizer.getBestBidsAsksArrayOverTime(
          orderBooksRequest(simulationId).orderBookDepth
        )

      assert(bestBidsAsks sameElements bestBidsAsksOverTime2(0))
      assert(bestBidsAsks sameElements bestBidsAsksOverTime2(1))
      assert(zerosArray sameElements bestBidsAsksOverTime2(2))
    }

    it(
      "When orders are placed on the order book then cancelled in the next simulation step, the 0th element of the array" +
        "returned from OrderBookFeaturizer.getBestBidsAsksArrayOverTime should be a 2D array with all zeros as entries."
    ) {
      val simulationId = getResult(Exchange.start(simulationStartRequest)).simulationId.get
      val simulationState = SimulationState.getOrFail(simulationId)
      implicit val accountState = simulationState.accountState
      implicit val simulationMetadata = simulationState.simulationMetadata
      implicit val matchingEngineState = simulationState.matchingEngineState
      implicit val orderBookFeaturizerState = simulationState.environmentState.orderBookFeaturizerState

      val (buyOrders, sellOrders) = placeOrders()

      Exchange.step(simulationId.toStepRequest.update(_.insertOrders := buyOrders ++ sellOrders))

      val bestBidsAsks = OrderBookFeaturizer.getBestBidsAsksArray(orderBooksRequest(simulationId).orderBookDepth)

      List(OrderSide.buy, OrderSide.sell).foreach { orderSide =>
        OrderBook
          .iterator(matchingEngineState.getOrderBookState(orderSide))
          .foreach(item => Exchange.cancelOrder(item._2))
      }

      Exchange.step(buildStepRequest(simulationId))

      val bestBidsAsksOverTime =
        OrderBookFeaturizer.getBestBidsAsksArrayOverTime(
          orderBooksRequest(simulationId).orderBookDepth
        )

      assert(bestBidsAsks sameElements bestBidsAsksOverTime(0))
      assert(zerosArray sameElements bestBidsAsksOverTime(1))
      assert(zerosArray sameElements bestBidsAsksOverTime(2))
    }

    it(
      "The array returned from OrderBookFeaturizer.getBestBidsAsksArray contain as rows 1D arrays of" +
        "the form (price_ask, volume_ask, price_bid, volume_bid, where the prices and volumes are the bid" +
        "and ask prices on the buy and sell order books. The rows in the array should be ordered in descending order," +
        "where the best bids and asks are in the top rows."
    ) {
      val simulationId = getResult(Exchange.start(simulationStartRequest)).simulationId.get
      val simulationState = SimulationState.getOrFail(simulationId)
      implicit val simulationMetadata = simulationState.simulationMetadata
      implicit val matchingEngineState = simulationState.matchingEngineState
      val (buyOrders, sellOrders) = placeOrders()

      Exchange.step(simulationId.toStepRequest.update(_.insertOrders := buyOrders ++ sellOrders))

      val bestBidsAsksArray = OrderBookFeaturizer.getBestBidsAsksArray(orderBooksRequest(simulationId).orderBookDepth)

      val expectedSellFeatures = sellOrders
        .map(order =>
          List(
            order.asMessage.getSellLimitOrder.price.toDouble,
            productVolume.toDouble
          )
        )
        .flatten

      val expectedBuyFeatures =
        buyOrders
          .map(order =>
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
      val simulationId = getResult(Exchange.start(simulationStartRequest)).simulationId.get
      implicit val matchingEngineState = SimulationState.getMatchingEngineStateOrFail(simulationId)
      val _buyLimitOrderRequest = buyLimitOrderRequest(simulationId)
      Range(0, 2).foreach { _ =>
        Account.placeBuyLimitOrder(_buyLimitOrderRequest)
        Exchange.step(buildStepRequest(simulationId))
      }
      val bestBidsAsksArray = OrderBookFeaturizer.getBestBidsAsksArray(orderBooksRequest(simulationId).orderBookDepth)

      bestBidsAsksArray(0)(2) === _buyLimitOrderRequest.price.toDouble
      bestBidsAsksArray(0)(3) === 2.0 * _buyLimitOrderRequest.size.toDouble
    }

    it(
      "The OrderBookFeaturizer should return an array of length 4 * orderBookDepth * SnapshotBuffer.getMaxSize." +
        "The first elements of the array should be equal to the elements returned by OrderBookFeaturizer.getBestBidsAsksArray." +
        "The latter elements should be all 0.0 "
    ) {
      val simulationId = getResult(Exchange.start(simulationStartRequest)).simulationId.get
      implicit val simulationState = SimulationState.getOrFail(simulationId)
      implicit val simulationMetadata = simulationState.simulationMetadata
      implicit val matchingEngineState = simulationState.matchingEngineState
      val (buyOrders, sellOrders) = placeOrders()

      Exchange.step(simulationId.toStepRequest.update(_.insertOrders := buyOrders ++ sellOrders))

      val orderBookFeatures =
        OrderBookFeaturizer.construct(new ObservationRequest(10))
      val bestBidsAsksArray = OrderBookFeaturizer.getBestBidsAsksArray(orderBooksRequest(simulationId).orderBookDepth)

      assert(
        4 * orderBooksRequest(simulationId).orderBookDepth * simulationMetadata.featureBufferSize == orderBookFeatures.size
      )
      assert(
        bestBidsAsksArray.flatten sameElements orderBookFeatures
          .slice(0, 4 * orderBooksRequest(simulationId).orderBookDepth)
      )
      assert(
        orderBookFeatures
          .drop(4 * orderBooksRequest(simulationId).orderBookDepth)
          .forall(element => element === 0.0)
      )
    }

    it(
      "The first row returned by the OrderBookFeaturizer should contain the prices and volumes of the best bids and asks" +
        "on the order book."
    ) {
      val simulationId = getResult(Exchange.start(simulationStartRequest)).simulationId.get
      implicit val simulationState = SimulationState.getOrFail(simulationId)
      implicit val simulationMetadata = simulationState.simulationMetadata
      implicit val matchingEngineState = simulationState.matchingEngineState

      val (buyOrders, sellOrders) = placeOrders()

      Exchange.step(simulationId.toStepRequest.update(_.insertOrders := buyOrders ++ sellOrders))

      val bestBidsAsksArray = OrderBookFeaturizer.getBestBidsAsksArray(orderBooksRequest(simulationId).orderBookDepth)
      val bestBuyOrder = OrderBook.maxOrder(matchingEngineState.buyOrderBookState).get
      val bestSellOrder = OrderBook.minOrder(matchingEngineState.sellOrderBookState).get

      assert(bestBidsAsksArray(0)(0) === bestSellOrder.price.toDouble)
      assert(bestBidsAsksArray(0)(1) === bestSellOrder.size.toDouble)
      assert(bestBidsAsksArray(0)(2) === bestBuyOrder.price.toDouble)
      assert(bestBidsAsksArray(0)(3) === bestBuyOrder.size.toDouble)
    }

    it("The arrays returned by OrderBookFeaturizer are sorted in order by the best bid and ask prices") {
      val simulationId = getResult(Exchange.start(simulationStartRequest)).simulationId.get
      implicit val simulationState = SimulationState.getOrFail(simulationId)
      implicit val simulationMetadata = simulationState.simulationMetadata
      implicit val matchingEngineState = simulationState.matchingEngineState

      val (buyOrders, sellOrders) = placeOrders(
        new ProductPrice(Right("910.00")),
        new ProductPrice(Right("1011.00"))
      )

      Exchange.step(simulationId.toStepRequest.update(_.insertOrders := sellOrders))

      val bestBidsAsksArray = OrderBookFeaturizer.getBestBidsAsksArray(orderBooksRequest(simulationId).orderBookDepth)

      val buyPrices = bestBidsAsksArray.map(row => row(2))
      val sellPrices = bestBidsAsksArray.map(row => row(0))

      assert(sellPrices sameElements sellPrices.sorted)
      assert(buyPrices sameElements buyPrices.sorted.reverse)
    }

  }
}

package co.firstorderlabs.coinbaseml.common.featurizers

import co.firstorderlabs.coinbaseml.common.Configs.testMode
import co.firstorderlabs.coinbaseml.common.utils.TestUtils.{SeqUtils, buildStepRequest, mean, std}
import co.firstorderlabs.coinbaseml.common.utils.Utils.getResult
import co.firstorderlabs.coinbaseml.fakebase.TestData.OrdersData
import co.firstorderlabs.coinbaseml.fakebase.TestData.RequestsData._
import co.firstorderlabs.coinbaseml.fakebase._
import co.firstorderlabs.common.currency.Configs.ProductPrice
import co.firstorderlabs.common.currency.Configs.ProductPrice.ProductVolume
import co.firstorderlabs.common.protos.events._
import co.firstorderlabs.common.protos.fakebase.StepRequest
import co.firstorderlabs.common.types.Types.OrderId
import org.scalactic.TolerantNumerics
import org.scalatest.funspec.AnyFunSpec

class TestTimeSeriesFeaturizer extends AnyFunSpec {
  testMode = true
  implicit val doubleEquality = TolerantNumerics.tolerantDoubleEquality(1e-10)
  val prices =
    List(new ProductPrice(Right("900.00")), new ProductPrice(Right("901.00")))
  val volumes =
    List(new ProductVolume(Right("1.01")), new ProductVolume(Right("1.1")))
  val productVolume = new ProductVolume(Right("1.01"))
  val expectedPriceMean = mean(prices.map(_.toDouble))
  val expectedPriceStd = std(prices.map(_.toDouble))
  val expectedSizeMean = mean(volumes.map(_.toDouble))
  val expectedSizeStd = std(volumes.map(_.toDouble))
  val testOrderId = OrderId("testId")

  describe("TimeSeriesFeaturizer") {
    it(
      "TimeSeriesFeaturizer should construct a feature vector of length SnapshotBuffer.maxSize * numChannels. When Exhange.receivedEvents" +
        "is empty the vector should have all zeros as elements."
    ) {
      val simulationId = getResult(Exchange.start(simulationStartRequest)).simulationId.get
      implicit val simulationState =
        SimulationState.getOrFail(simulationId)
      val simulationMetadata = simulationState.simulationMetadata
      Exchange.step(buildStepRequest(simulationId))
      val features = TimeSeriesVectorizer.construct(observationRequest)

      assert(
        features.size == timeSeriesOrderBookConfigs.featureBufferSize * TimeSeriesVectorizer.numChannels
      )
      assert(features.forall(_ == 0))
    }

    it(
      "The constructed feature vector should have the following entries when buy limit orders are placed into receivedEvents"
    ) {
      val simulationId = getResult(Exchange.start(simulationStartRequest)).simulationId.get
      implicit val simulationState =
        SimulationState.getOrFail(simulationId)

      val buyOrders = List(
        BuyLimitOrder(
          testOrderId,
          OrderStatus.received,
          prices(0),
          ProductPrice.productId,
          OrderSide.buy,
          volumes(0)
        ),
        BuyLimitOrder(
          testOrderId,
          OrderStatus.received,
          prices(1),
          ProductPrice.productId,
          OrderSide.buy,
          volumes(1)
        )
      )

      Exchange.step(simulationId.toStepRequest.update(_.insertOrders := buyOrders))
      val features = TimeSeriesVectorizer.construct(observationRequest)

      assert(buyOrders.size === features(2))
      assert(expectedPriceMean === features(14))
      assert(expectedPriceStd === features(15))
      assert(expectedSizeMean === features(22))
      assert(expectedSizeStd === features(23))
      assert(features.dropIndices(2, 14, 15, 22, 23).containsOnly(0))
    }

    it(
      "The constructed feature vector should have the following entries when sell limit orders are placed into receivedEvents"
    ) {
      val simulationId = getResult(Exchange.start(simulationStartRequest)).simulationId.get
      implicit val simulationState =
        SimulationState.getOrFail(simulationId)

      val sellOrders = List(
        SellLimitOrder(
          testOrderId,
          OrderStatus.received,
          prices(0),
          ProductPrice.productId,
          OrderSide.sell,
          volumes(0)
        ),
        SellLimitOrder(
          testOrderId,
          OrderStatus.received,
          prices(1),
          ProductPrice.productId,
          OrderSide.sell,
          volumes(1)
        )
      )

      Exchange.step(simulationId.toStepRequest.update(_.insertOrders := sellOrders))
      val features = TimeSeriesVectorizer.construct(observationRequest)

      assert(sellOrders.size === features(5))
      assert(expectedPriceMean === features(16))
      assert(expectedPriceStd === features(17))
      assert(expectedSizeMean === features(24))
      assert(expectedSizeStd === features(25))
      assert(features.dropIndices(5, 16, 17, 24, 25).containsOnly(0))
    }

    it(
      "The constructed feature vector should have the following entries when buy cancellations are placed into receivedEvents"
    ) {
      val simulationId = getResult(Exchange.start(simulationStartRequest)).simulationId.get
      implicit val simulationState =
        SimulationState.getOrFail(simulationId)
      val cancellations = prices.map(price =>
        Cancellation(
          OrderId("testId"),
          price,
          ProductPrice.productId,
          OrderSide.buy
        )
      )

      Exchange.step(simulationId.toStepRequest.update(_.insertCancellations := cancellations))
      val features = TimeSeriesVectorizer.construct(observationRequest)

      assert(cancellations.size === features(0))
      assert(expectedPriceMean === features(6))
      assert(expectedPriceStd === features(7))
      assert(features.dropIndices(0, 6, 7).containsOnly(0.0))
    }

    it(
      "The constructed feature vector should have the following entries when sell cancellations are placed into receivedEvents"
    ) {
      val simulationId = getResult(Exchange.start(simulationStartRequest)).simulationId.get
      implicit val simulationState =
        SimulationState.getOrFail(simulationId)
      val cancellations = prices.map(price =>
        Cancellation(
          OrderId("testId"),
          price,
          ProductPrice.productId,
          OrderSide.sell
        )
      )

      Exchange.step(simulationId.toStepRequest.update(_.insertCancellations := cancellations))
      val features = TimeSeriesVectorizer.construct(observationRequest)

      assert(cancellations.size === features(3))
      assert(expectedPriceMean === features(8))
      assert(expectedPriceStd === features(9))
      assert(features.dropIndices(3, 8, 9).containsOnly(0.0))
    }

    it(
      "The constructed feature vector should have the following entries when buy matches are created."
    ) {
      val simulationId = getResult(Exchange.start(simulationStartRequest)).simulationId.get
      implicit val simulationState =
        SimulationState.getOrFail(simulationId)
      implicit val simulationMetadata = simulationState.simulationMetadata
      val buyOrders = OrdersData.insertBuyOrders(
        maxPrice = new ProductPrice(Right("900.00")),
        numOrders = 2
      )

      Exchange.step(simulationId.toStepRequest.update(_.insertOrders := buyOrders))

      val sellOrders = OrdersData.insertSellOrders(
        minPrice = new ProductPrice(Right("890.00")),
        numOrders = 2
      )

      Exchange.step(simulationId.toStepRequest.update(_.insertOrders := sellOrders))

      val features = TimeSeriesVectorizer.construct(observationRequest)

      val expectedBuyMatchPriceMean = mean(
        buyOrders
          .map(order => order.asMessage.getBuyLimitOrder.price.toDouble)
      )
      val expectedBuyMatchPriceStd = std(
        buyOrders
          .map(order => order.asMessage.getBuyLimitOrder.price.toDouble)
      )

      assert(
        sellOrders.size === features(1)
      ) // There should me one buy match for each sellOrder placed
      assert(expectedBuyMatchPriceMean === features(10))
      assert(expectedBuyMatchPriceStd === features(11))
    }

    it(
      "The constructed feature vector should have the following entries when sell matches are created."
    ) {
      val simulationId = getResult(Exchange.start(simulationStartRequest)).simulationId.get
      implicit val simulationState =
        SimulationState.getOrFail(simulationId)
      implicit val simulationMetadata = simulationState.simulationMetadata
      val sellOrders = OrdersData.insertSellOrders(
        minPrice = new ProductPrice(Right("890.00")),
        numOrders = 2
      )

      Exchange.step(simulationId.toStepRequest.update(_.insertOrders := sellOrders))

      val buyOrders = OrdersData.insertBuyOrders(
        maxPrice = new ProductPrice(Right("900.00")),
        numOrders = 2
      )

      Exchange.step(simulationId.toStepRequest.update(_.insertOrders := buyOrders))

      val features = TimeSeriesVectorizer.construct(observationRequest)

      val expectedSellMatchPriceMean = mean(
        sellOrders
          .map(order => order.asMessage.getSellLimitOrder.price.toDouble)
      )
      val expectedSellMatchPriceStd = std(
        sellOrders
          .map(order => order.asMessage.getSellLimitOrder.price.toDouble)
      )

      assert(
        sellOrders.size === features(4)
      ) // There should me one sell match for each buyOrder placed
      assert(expectedSellMatchPriceMean === features(12))
      assert(expectedSellMatchPriceStd === features(13))
    }

    it(
      "The features calculated from receivedEvents should be pushed to the back of the constructed" +
        "feature vector as the simulation is advanced."
    ) {
      val simulationId = getResult(Exchange.start(simulationStartRequest)).simulationId.get
      implicit val simulationState =
        SimulationState.getOrFail(simulationId)
      implicit val simulationMetadata = simulationState.simulationMetadata
      val sellOrders = OrdersData.insertSellOrders(
        minPrice = new ProductPrice(Right("890.00")),
        numOrders = 2
      )

      val buyOrders = OrdersData.insertBuyOrders(
        maxPrice = new ProductPrice(Right("900.00")),
        numOrders = 2
      )

      val buyCancellations = prices.map(price =>
        Cancellation(
          OrderId("testId"),
          price,
          ProductPrice.productId,
          OrderSide.buy
        )
      )

      val sellCancellations = prices.map(price =>
        Cancellation(
          OrderId("testId"),
          price,
          ProductPrice.productId,
          OrderSide.sell
        )
      )

      Exchange.step(
        StepRequest(
          buyOrders ++ sellOrders,
          buyCancellations ++ sellCancellations,
          simulationId = Some(simulationId)
        )
      )

      val stepRequest = buildStepRequest(simulationId)
      val features1 = TimeSeriesVectorizer.construct(observationRequest)
      Exchange.step(stepRequest)
      val features2 = TimeSeriesVectorizer.construct(observationRequest)
      Exchange.step(stepRequest)
      val features3 = TimeSeriesVectorizer.construct(observationRequest)
      Exchange.step(stepRequest)
      val features4 = TimeSeriesVectorizer.construct(observationRequest)

      val numChannels = TimeSeriesVectorizer.numChannels
      assert(
        features1.take(numChannels) sameElements features2
          .drop(numChannels)
          .dropRight(numChannels)
      )
      assert(
        features2.dropSlice(numChannels, 2 * numChannels).containsOnly(0.0)
      )
      assert(
        features1.take(numChannels) sameElements features3.drop(2 * numChannels)
      )
      assert(features3.dropRight(numChannels).containsOnly(0.0))
      assert(features4.containsOnly(0.0))
    }
  }
}

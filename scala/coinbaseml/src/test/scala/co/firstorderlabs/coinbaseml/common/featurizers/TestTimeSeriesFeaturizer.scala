package co.firstorderlabs.coinbaseml.common.featurizers

import co.firstorderlabs.coinbaseml.common.utils.TestUtils.{SeqUtils, mean, std}
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
  Configs.testMode = true
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
      Exchange.start(simulationStartRequest)
      Exchange.step(Constants.emptyStepRequest)
      val features = TimeSeriesFeaturizer.construct(observationRequest)

      assert(
        features.size == Exchange.getSimulationMetadata.snapshotBufferSize * TimeSeriesFeaturizer.numChannels
      )
      assert(features.forall(_ == 0))
    }

    it(
      "The constructed feature vector should have the following entries when buy limit orders are placed into receivedEvents"
    ) {
      Exchange.start(simulationStartRequest)

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

      Exchange.step(StepRequest(insertOrders = buyOrders))
      val features = TimeSeriesFeaturizer.construct(observationRequest)

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
      Exchange.start(simulationStartRequest)

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

      Exchange.step(StepRequest(insertOrders = sellOrders))
      val features = TimeSeriesFeaturizer.construct(observationRequest)

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
      Exchange.start(simulationStartRequest)
      val cancellations = prices.map(
        price =>
          Cancellation(
            OrderId("testId"),
            price,
            ProductPrice.productId,
            OrderSide.buy
        )
      )

      Exchange.step(StepRequest(insertCancellations = cancellations))
      val features = TimeSeriesFeaturizer.construct(observationRequest)

      assert(cancellations.size === features(0))
      assert(expectedPriceMean === features(6))
      assert(expectedPriceStd === features(7))
      assert(features.dropIndices(0, 6, 7).containsOnly(0.0))
    }

    it(
      "The constructed feature vector should have the following entries when sell cancellations are placed into receivedEvents"
    ) {
      Exchange.start(simulationStartRequest)
      val cancellations = prices.map(
        price =>
          Cancellation(
            OrderId("testId"),
            price,
            ProductPrice.productId,
            OrderSide.sell
        )
      )

      Exchange.step(StepRequest(insertCancellations = cancellations))
      val features = TimeSeriesFeaturizer.construct(observationRequest)

      assert(cancellations.size === features(3))
      assert(expectedPriceMean === features(8))
      assert(expectedPriceStd === features(9))
      assert(features.dropIndices(3, 8, 9).containsOnly(0.0))
    }

    it(
      "The constructed feature vector should have the following entries when buy matches are created."
    ) {
      Exchange.start(simulationStartRequest)
      val buyOrders = OrdersData.insertBuyOrders(
        maxPrice = new ProductPrice(Right("900.00")),
        numOrders = 2
      )

      Exchange.step(StepRequest(insertOrders = buyOrders))

      val sellOrders = OrdersData.insertSellOrders(
        minPrice = new ProductPrice(Right("890.00")),
        numOrders = 2
      )

      Exchange.step(StepRequest(insertOrders = sellOrders))

      val features = TimeSeriesFeaturizer.construct(observationRequest)

      val expectedBuyMatchPriceMean = mean(
        buyOrders
          .map(order => order.asMessage.getBuyLimitOrder.price.toDouble)
      )
      val expectedBuyMatchPriceStd = std(
        buyOrders
          .map(order => order.asMessage.getBuyLimitOrder.price.toDouble)
      )

      assert(sellOrders.size === features(1)) // There should me one buy match for each sellOrder placed
      assert(expectedBuyMatchPriceMean === features(10))
      assert(expectedBuyMatchPriceStd === features(11))
    }

    it(
      "The constructed feature vector should have the following entries when sell matches are created."
    ) {
      Exchange.start(simulationStartRequest)
      val sellOrders = OrdersData.insertSellOrders(
        minPrice = new ProductPrice(Right("890.00")),
        numOrders = 2
      )

      Exchange.step(StepRequest(insertOrders = sellOrders))

      val buyOrders = OrdersData.insertBuyOrders(
        maxPrice = new ProductPrice(Right("900.00")),
        numOrders = 2
      )

      Exchange.step(StepRequest(insertOrders = buyOrders))

      val features = TimeSeriesFeaturizer.construct(observationRequest)

      val expectedSellMatchPriceMean = mean(
        sellOrders
          .map(order => order.asMessage.getSellLimitOrder.price.toDouble)
      )
      val expectedSellMatchPriceStd = std(
        sellOrders
          .map(order => order.asMessage.getSellLimitOrder.price.toDouble)
      )

      assert(sellOrders.size === features(4)) // There should me one sell match for each buyOrder placed
      assert(expectedSellMatchPriceMean === features(12))
      assert(expectedSellMatchPriceStd === features(13))
    }

    it("The features calculated from receivedEvents should be pushed to the back of the constructed" +
      "feature vector as the simulation is advanced.") {
      Exchange.start(simulationStartRequestWarmup)
      val sellOrders = OrdersData.insertSellOrders(
        minPrice = new ProductPrice(Right("890.00")),
        numOrders = 2
      )

      val buyOrders = OrdersData.insertBuyOrders(
        maxPrice = new ProductPrice(Right("900.00")),
        numOrders = 2
      )

      val buyCancellations = prices.map(
        price =>
          Cancellation(
            OrderId("testId"),
            price,
            ProductPrice.productId,
            OrderSide.buy
        )
      )

      val sellCancellations = prices.map(
        price =>
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
          buyCancellations ++ sellCancellations
        )
      )

      val features1 = TimeSeriesFeaturizer.construct(observationRequest)
      Exchange.step(Constants.emptyStepRequest)
      val features2 = TimeSeriesFeaturizer.construct(observationRequest)
      Exchange.step(Constants.emptyStepRequest)
      val features3 = TimeSeriesFeaturizer.construct(observationRequest)
      Exchange.step(Constants.emptyStepRequest)
      val features4 = TimeSeriesFeaturizer.construct(observationRequest)

      val numChannels = TimeSeriesFeaturizer.numChannels
      assert(
        features1.take(numChannels) sameElements features2
          .drop(numChannels)
          .dropRight(numChannels)
      )
      assert(features2.dropSlice(numChannels, 2 * numChannels).containsOnly(0.0))
      assert(
        features1.take(numChannels) sameElements features3.drop(2 * numChannels)
      )
      assert(features3.dropRight(numChannels).containsOnly(0.0))
      assert(features4.containsOnly(0.0))
    }
    it("test new") {
  Configs.testMode = true
  implicit val doubleEquality = TolerantNumerics.tolerantDoubleEquality(1e-10)
  val prices =
    List(new ProductPrice(Right("900.00")), new ProductPrice(Right("901.00")))
      Exchange.start(simulationStartRequestWarmup)
      val sellOrders = OrdersData.insertSellOrders(
        minPrice = new ProductPrice(Right("890.00")),
        numOrders = 2
      )

      val buyOrders = OrdersData.insertBuyOrders(
        maxPrice = new ProductPrice(Right("900.00")),
        numOrders = 2
      )

      val buyCancellations = prices.map(
        price =>
          Cancellation(
            OrderId("testId"),
            price,
            ProductPrice.productId,
            OrderSide.buy
          )
      )

      val sellCancellations = prices.map(
        price =>
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
          buyCancellations ++ sellCancellations
        )
      )

      TimeSeriesFeaturizer.step
    }
  }
}

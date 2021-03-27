package co.firstorderlabs.coinbaseml.common.actions.actionizers

import co.firstorderlabs.coinbaseml.common.actions.actionizers.Indicators.KaufmanAdaptiveMovingAverage
import co.firstorderlabs.coinbaseml.common.utils.TestUtils.DoubleUtils
import co.firstorderlabs.common.protos.indicators.{
  KaufmanAdaptiveMovingAverageConfigs,
  KaufmanAdaptiveMovingAverageState
}
import org.scalatest.funspec.AnyFunSpec

class TestIndicators extends AnyFunSpec {
  val numPeriods = 4
  val prices = List(1.243, -6.5345, 7.543435, 2.32423, 6554, 323.435)
  describe("KaufmanAdaptiveMovingAverage") {
    it(
      "The priceBuffer should never be larger than numPeriods. It should obey FIFO logic."
    ) {
      val configs = KaufmanAdaptiveMovingAverageConfigs(numPeriods)

      val priceBuffer = prices.map {
        var state = new KaufmanAdaptiveMovingAverageState;
        p =>
          state = KaufmanAdaptiveMovingAverage.update(p)(configs, state)
          state.priceBuffer
      }.last
      assert(priceBuffer.size == numPeriods)
      assert(priceBuffer.head ~= prices(prices.size - numPeriods))
      assert(priceBuffer.last ~= prices.last)
    }

    it(
      "The signal method should return the abs difference between the most recent price and the oldest one in the buffer."
    ) {
      implicit val configs = KaufmanAdaptiveMovingAverageConfigs(numPeriods)
      implicit val state = prices.map {
        var state = new KaufmanAdaptiveMovingAverageState;
        p =>
          state = KaufmanAdaptiveMovingAverage.update(p)(configs, state)
          state
      }.last
      assert(
        KaufmanAdaptiveMovingAverage.signal ~= (prices.last - prices(
          prices.size - numPeriods
        ))
      )
    }

    it(
      "The noise method returns the sum of the difference beteween consecutive prices in the priceBuffer"
    ) {
      implicit val configs = KaufmanAdaptiveMovingAverageConfigs(numPeriods)
      implicit val state = prices
        .take(3)
        .map {
          var state = new KaufmanAdaptiveMovingAverageState;
          p => KaufmanAdaptiveMovingAverage.update(p)(configs, state)
        }
        .last
      assert(
        KaufmanAdaptiveMovingAverage.noise == (math
          .abs(prices(1) - prices(0)) + math
          .abs(prices(2) - prices(1)))
      )
    }

    it("The efficiencyRatio should be the ratio of the signal to the noise.") {
      implicit val configs = KaufmanAdaptiveMovingAverageConfigs(3)
      val _prices = prices.take(3)
      implicit val state = _prices
        .take(3)
        .map {
          var state = new KaufmanAdaptiveMovingAverageState;
          p => KaufmanAdaptiveMovingAverage.update(p)(configs, state)
        }
        .last

      val expectedSignal = math.abs(_prices.last - _prices.head)
      val expectedNoise =
        math.abs(prices(1) - prices(0)) + math.abs(prices(2) - prices(1))
      assert(
        KaufmanAdaptiveMovingAverage.efficiencyRatio ~= expectedSignal / expectedNoise
      )
    }

    it(
      "The efficiencyRatio should be 1.0 when the price is trending up, down, or constant."
    ) {
      val constantsPrices = List.fill(5)(0.0)
      val increasingPrices =
        List(1.243, 6.5345, 7.543435, 10.32423, 12.6554, 323.435)
      val decreasingPrices = increasingPrices.map(p => -p)
      List(constantsPrices, increasingPrices, decreasingPrices).foreach {
        prices =>
          implicit val configs = KaufmanAdaptiveMovingAverageConfigs(numPeriods)
          prices.foreach {
            var state = new KaufmanAdaptiveMovingAverageState;
            p =>
            state = KaufmanAdaptiveMovingAverage.update(math.abs(p))(configs, state)
            assert(KaufmanAdaptiveMovingAverage.efficiencyRatio(state) ~= 1.0)
          }
      }
    }
  }
}

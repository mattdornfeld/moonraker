package co.firstorderlabs.coinbaseml.common.actions.actionizers

import co.firstorderlabs.coinbaseml.common.actions.actionizers.Indicators.KaufmanAdaptiveMovingAverage
import co.firstorderlabs.coinbaseml.common.utils.TestUtils.DoubleUtils
import org.scalatest.funspec.AnyFunSpec

class TestIndicators extends AnyFunSpec {
  val numPeriods = 4
  val prices = List(1.243, -6.5345, 7.543435, 2.32423, 6554, 323.435)
  describe("KaufmanAdaptiveMovingAverage") {
    it(
      "The priceBuffer should never be larger than numPeriods. It should obey FIFO logic."
    ) {
      val movingAverage = KaufmanAdaptiveMovingAverage(numPeriods, 0.0, 0.0)
      prices.foreach(p => movingAverage.update(p))
      val priceBuffer = movingAverage.priceBuffer
      println(priceBuffer)
      assert(priceBuffer.size == numPeriods)
      assert(priceBuffer.head ~= prices(prices.size - numPeriods))
      assert(priceBuffer.last ~= prices.last)
    }

    it(
      "The signal method should return the abs difference between the most recent price and the oldest one in the buffer."
    ) {
      val movingAverage = KaufmanAdaptiveMovingAverage(numPeriods, 0.0, 0.0)
      prices.foreach(p => movingAverage.update(p))
      assert(
        movingAverage.signal ~= (prices.last - prices(prices.size - numPeriods))
      )
    }

    it(
      "The noise method returns the sum of the difference beteween consecutive prices in the priceBuffer"
    ) {
      val movingAverage = KaufmanAdaptiveMovingAverage(numPeriods)
      prices.take(3).foreach(p => movingAverage.update(p))
      assert(
        movingAverage.noise == (math.abs(prices(1) - prices(0)) + math
          .abs(prices(2) - prices(1)))
      )
    }

    it("The efficiencyRatio should be the ratio of the signal to the noise.") {
      val movingAverage = KaufmanAdaptiveMovingAverage(3)
      val _prices = prices.take(3)
      _prices.foreach(p => movingAverage.update(p))

      val expectedSignal = math.abs(_prices.last - _prices.head)
      val expectedNoise =
        math.abs(prices(1) - prices(0)) + math.abs(prices(2) - prices(1))
      assert(movingAverage.efficiencyRatio ~= expectedSignal / expectedNoise)
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
          val movingAverage = KaufmanAdaptiveMovingAverage(numPeriods, 0.0, 0.0)
          prices.foreach { p =>
            movingAverage.update(math.abs(p))
            assert(movingAverage.efficiencyRatio ~= 1.0)
          }
      }
    }
  }
}

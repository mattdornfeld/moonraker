package co.firstorderlabs.coinbaseml.common.actions.actionizers.Indicators

import scala.collection.mutable.ListBuffer

sealed trait Indicator {
  var value: Double
  var previousValue: Double

  def copy: Indicator

  def crossAbove(that: Indicator): Boolean =
    previousValue <= that.previousValue && value > that.value

  def crossBelow(that: Indicator): Boolean =
    previousValue >= that.previousValue && value < that.value
}

sealed trait MovingIndicator extends Indicator {
  def alpha: Double

  def transform(samples: Seq[Double]): Seq[Double] =
    samples.map { sample =>
      update(sample)
      value
    }

  def update(sample: Double): Unit
}

sealed trait MovingAverage extends MovingIndicator {
  def update(sample: Double): Unit = {
    previousValue = value
    value += alpha * (sample - value)
  }
}

sealed trait MovingVariance extends MovingIndicator {
  def beta(price: Double): Double

  def update(sample: Double): Unit = {
    previousValue = value
    value = alpha * value + beta(sample)
  }
}

final case class ExponentialMovingAverage(
    alpha: Double,
    var value: Double,
    var previousValue: Double
) extends MovingAverage {
  override def copy: ExponentialMovingAverage =
    ExponentialMovingAverage(alpha, value, previousValue)
}

final case class KaufmanAdaptiveMovingAverage(
    windowSize: Int,
    var value: Double = 0.0,
    var previousValue: Double = 0.0,
    fastPeriod: Int = 2,
    slowPeriod: Int = 30,
    priceBuffer: ListBuffer[Double] = new ListBuffer
) extends MovingAverage {
  private val fastest = 2.0 / (fastPeriod + 1)
  private val slowest = 2.0 / (slowPeriod + 1)

  def efficiencyRatio: Double = if (noise > 0.0) signal / noise else 1.0

  def noise: Double =
    priceBuffer
      .drop(1)
      .zip(priceBuffer.dropRight(1))
      .map(p => math.abs(p._1 - p._2))
      .reduceOption(_ + _)
      .getOrElse(0.0)

  def signal: Double =
    math.abs(priceBuffer.last - priceBuffer.head)

  override def alpha: Double = {
    val _efficiencyRatio =
      if (priceBuffer.size < windowSize) 1.0 else efficiencyRatio
    math.pow(_efficiencyRatio * (fastest - slowest) + slowest, 2)
  }

  override def copy: KaufmanAdaptiveMovingAverage =
    KaufmanAdaptiveMovingAverage(
      windowSize,
      value,
      previousValue,
      fastPeriod,
      slowPeriod,
      priceBuffer
    )

  override def update(sample: Double): Unit = {
    while (priceBuffer.size >= windowSize) {
      priceBuffer.remove(0)
    }
    priceBuffer.append(sample)
    super.update(sample)
  }
}

final case class KaufmanAdaptiveMovingVariance(
    windowSize: Int,
    movingAverageValue: Double = 0.0,
    movingAveragePreviousValue: Double = 0.0,
    fastPeriod: Int = 2,
    slowPeriod: Int = 30,
    var value: Double = 0.0,
    var previousValue: Double = 0.0
) extends MovingVariance {
  val movingAverage = KaufmanAdaptiveMovingAverage(
    windowSize = windowSize,
    value = movingAverageValue,
    previousValue = movingAveragePreviousValue,
    fastPeriod = fastPeriod,
    slowPeriod = slowPeriod
  )

  def delta(price: Double): Double =
    price - movingAverage.previousValue

  override def alpha: Double = 1 - movingAverage.alpha

  override def beta(price: Double): Double =
    movingAverage.alpha * alpha * math.pow(delta(price), 2)

  override def copy: KaufmanAdaptiveMovingVariance =
    KaufmanAdaptiveMovingVariance(
      windowSize = windowSize,
      movingAverageValue = movingAverage.value,
      movingAveragePreviousValue = movingAverage.previousValue,
      value = value,
      previousValue = previousValue,
      fastPeriod = fastPeriod,
      slowPeriod = slowPeriod
    )

  override def update(sample: Double): Unit = {
    movingAverage.update(sample)
    super.update(sample)
  }
}

final case class ExponentialMovingVariance(
    movingAverageAlpha: Double,
    movingAverageValue: Double = 0.0,
    movingAveragePreviousValue: Double = 0.0,
    var value: Double = 0.0,
    var previousValue: Double = 0.0
) extends MovingVariance {
  val alpha = 1 - movingAverageAlpha
  val exponentialMovingAverage = ExponentialMovingAverage(
    movingAverageAlpha,
    movingAverageValue,
    movingAveragePreviousValue
  )

  def delta(price: Double): Double =
    price - exponentialMovingAverage.previousValue

  override def beta(price: Double): Double =
    movingAverageAlpha * alpha * math.pow(delta(price), 2)

  override def copy: ExponentialMovingVariance =
    ExponentialMovingVariance(
      movingAverageAlpha,
      exponentialMovingAverage.value,
      exponentialMovingAverage.previousValue,
      value,
      previousValue
    )

  override def update(sample: Double): Unit = {
    exponentialMovingAverage.update(sample)
    super.update(sample)
  }
}

final case class OnBookVolume(var value: Double, var previousValue: Double)
    extends Indicator {

  override def copy: OnBookVolume =
    OnBookVolume(value, previousValue)

  def update(priceDelta: Double, volume: Double): Unit = {
    previousValue = value
    if (priceDelta > 0) { value += volume }
    else if (priceDelta < 0) { value -= volume }
    else {}
  }

}

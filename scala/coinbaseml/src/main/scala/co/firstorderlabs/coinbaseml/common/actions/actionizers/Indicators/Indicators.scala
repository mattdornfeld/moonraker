package co.firstorderlabs.coinbaseml.common.actions.actionizers.Indicators

import co.firstorderlabs.common.protos.indicators._
import co.firstorderlabs.common.types.Indicators.{IndicatorConfigs, IndicatorState, MovingAverageConfigs, MovingAverageState, MovingIndicatorConfigs, MovingIndicatorState, MovingVarianceConfigs, MovingVarianceState}

sealed trait Indicator[A <: IndicatorConfigs, B <: IndicatorState] {
  def crossAbove(
      indicatorState: IndicatorState,
      that: IndicatorState
  ): Boolean =
    indicatorState.previousValue <= that.previousValue && indicatorState.value > that.value

  def crossBelow(
      indicatorState: IndicatorState,
      that: IndicatorState
  ): Boolean =
    indicatorState.previousValue >= that.previousValue && indicatorState.value < that.value

  def derivative(delta: Double = 1.0)(implicit
      indicatorState: IndicatorState
  ): Double = (indicatorState.value - indicatorState.previousValue) / delta
}

sealed trait MovingIndicator[
    A <: MovingIndicatorConfigs,
    B <: MovingIndicatorState
] extends Indicator[A, B] {
  def alpha(implicit
      indicatorConfigs: A,
      indicatorState: B
  ): Double

  def transform(
      samples: Seq[Double]
  )(implicit
      indicatorConfigs: A,
      indicatorState: B
  ): (Seq[Double], B) = {
    var _indicatorState = indicatorState
    val values = samples.map { sample =>
      _indicatorState = update(sample)
      _indicatorState.value
    }
    (values, _indicatorState)
  }

  def update(sample: Double)(implicit
      indicatorConfigs: A,
      indicatorState: B
  ): B
}

sealed trait MovingAverage[A <: MovingAverageConfigs, B <: MovingAverageState]
    extends MovingIndicator[A, B]

sealed trait MovingVariance[
    A <: MovingVarianceConfigs,
    B <: MovingVarianceState
] extends MovingIndicator[A, B] {
  def beta(price: Double)(implicit
      indicatorConfigs: A,
      indicatorState: B
  ): Double

  def delta(
      price: Double
  )(implicit movingVarianceState: B): Double

  def bollingerBandValues(
      bandSize: Double
  )(implicit indicatorState: B): (Double, Double) = {
    val std = math.pow(indicatorState.value, 0.5)
    (
      indicatorState.movingAverageState.value - bandSize * std,
      indicatorState.movingAverageState.value + bandSize * std
    )
  }
}

object ExponentialMovingAverage
    extends MovingAverage[
      ExponentialMovingAverageConfigs,
      ExponentialMovingAverageState
    ] {
  override def alpha(implicit
      indicatorConfigs: ExponentialMovingAverageConfigs,
      indicatorState: ExponentialMovingAverageState
  ): Double =
    indicatorConfigs match {
      case indicatorConfigs: ExponentialMovingAverageConfigs =>
        2.0 / indicatorConfigs.windowSize
    }

  override def update(
      sample: Double
  )(implicit
      indicatorConfigs: ExponentialMovingAverageConfigs,
      indicatorState: ExponentialMovingAverageState
  ): ExponentialMovingAverageState =
    indicatorState match {
      case indicatorState: ExponentialMovingAverageState => {
        val v = indicatorState.value
        indicatorState.update(
          _.previousValue := v,
          _.value := v + alpha(indicatorConfigs, indicatorState) * (sample - v)
        )
      }
    }
}

object KaufmanAdaptiveMovingAverage
    extends MovingAverage[
      KaufmanAdaptiveMovingAverageConfigs,
      KaufmanAdaptiveMovingAverageState
    ] {
  private val fastest = 2.0 / (2 + 1)
  private val slowest = 2.0 / (30 + 1)

  def efficiencyRatio(implicit
      indicatorState: KaufmanAdaptiveMovingAverageState
  ): Double = if (noise > 0.0) signal / noise else 1.0

  def noise(implicit
      indicatorState: KaufmanAdaptiveMovingAverageState
  ): Double =
    indicatorState.priceBuffer
      .drop(1)
      .zip(indicatorState.priceBuffer.dropRight(1))
      .map(p => math.abs(p._1 - p._2))
      .reduceOption(_ + _)
      .getOrElse(0.0)

  def signal(implicit
      indicatorState: KaufmanAdaptiveMovingAverageState
  ): Double =
    math.abs(indicatorState.priceBuffer.last - indicatorState.priceBuffer.head)

  override def alpha(implicit
      indicatorConfigs: KaufmanAdaptiveMovingAverageConfigs,
      indicatorState: KaufmanAdaptiveMovingAverageState
  ): Double =
    (indicatorConfigs, indicatorState) match {
      case (
            configs: KaufmanAdaptiveMovingAverageConfigs,
            state: KaufmanAdaptiveMovingAverageState
          ) => {
        val _efficiencyRatio =
          if (state.priceBuffer.size < configs.windowSize) 1.0
          else efficiencyRatio(state)
        math.pow(_efficiencyRatio * (fastest - slowest) + slowest, 2)
      }
    }

  override def update(sample: Double)(implicit
      indicatorConfigs: KaufmanAdaptiveMovingAverageConfigs,
      indicatorState: KaufmanAdaptiveMovingAverageState
  ): KaufmanAdaptiveMovingAverageState = {
    (indicatorConfigs, indicatorState) match {
      case (
            configs: KaufmanAdaptiveMovingAverageConfigs,
            state: KaufmanAdaptiveMovingAverageState
          ) => {
        while (state.priceBuffer.size >= configs.windowSize) {
          state.priceBuffer.remove(0)
        }
        state.priceBuffer.append(sample)
        state.update(
          _.previousValue := state.value,
          _.value := state.value + alpha * (sample - state.value)
        )
      }
    }
  }
}

object KaufmanAdaptiveMovingVariance
    extends MovingVariance[
      KaufmanAdaptiveMovingVarianceConfigs,
      KaufmanAdaptiveMovingVarianceState
    ] {
  override def alpha(implicit
      indicatorConfigs: KaufmanAdaptiveMovingVarianceConfigs,
      indicatorState: KaufmanAdaptiveMovingVarianceState
  ): Double =
    1 - KaufmanAdaptiveMovingAverage.alpha(
      indicatorConfigs.movingAverageConfigs,
      indicatorState.movingAverageState
    )

  override def beta(price: Double)(implicit
      indicatorConfigs: KaufmanAdaptiveMovingVarianceConfigs,
      indicatorState: KaufmanAdaptiveMovingVarianceState
  ): Double =
    KaufmanAdaptiveMovingAverage.alpha(
      indicatorConfigs.movingAverageConfigs,
      indicatorState.movingAverageState
    ) * alpha * math.pow(delta(price), 2)

  override def delta(
      price: Double
  )(implicit movingVarianceState: KaufmanAdaptiveMovingVarianceState): Double =
    price - movingVarianceState.movingAverageState.previousValue

  override def update(sample: Double)(implicit
      indicatorConfigs: KaufmanAdaptiveMovingVarianceConfigs,
      indicatorState: KaufmanAdaptiveMovingVarianceState
  ): KaufmanAdaptiveMovingVarianceState = {
    (indicatorConfigs, indicatorState) match {
      case (
            configs: KaufmanAdaptiveMovingVarianceConfigs,
            state: KaufmanAdaptiveMovingVarianceState
          ) =>
        val updatedMovingAverageState =
          KaufmanAdaptiveMovingAverage.update(sample)(
            configs.movingAverageConfigs,
            state.movingAverageState
          )

        state.update(
          _.previousValue := state.value,
          _.value := alpha * state.value + beta(sample)(configs, state),
          _.movingAverageState := updatedMovingAverageState
        )
    }
  }
}

object ExponentialMovingVariance
    extends MovingVariance[
      ExponentialMovingVarianceConfigs,
      ExponentialMovingVarianceState
    ] {
  override def alpha(implicit
      indicatorConfigs: ExponentialMovingVarianceConfigs,
      indicatorState: ExponentialMovingVarianceState
  ): Double =
    1 - ExponentialMovingAverage.alpha(
      indicatorConfigs.movingAverageConfigs,
      indicatorState.movingAverageState
    )

  override def beta(price: Double)(implicit
      indicatorConfigs: ExponentialMovingVarianceConfigs,
      indicatorState: ExponentialMovingVarianceState
  ): Double =
    ExponentialMovingAverage.alpha(
      indicatorConfigs.movingAverageConfigs,
      indicatorState.movingAverageState
    ) * alpha * math.pow(delta(price), 2)

  override def delta(
      price: Double
  )(implicit movingVarianceState: ExponentialMovingVarianceState): Double =
    price - movingVarianceState.movingAverageState.previousValue

  override def update(sample: Double)(implicit
      indicatorConfigs: ExponentialMovingVarianceConfigs,
      indicatorState: ExponentialMovingVarianceState
  ): ExponentialMovingVarianceState = {
    (indicatorConfigs, indicatorState) match {
      case (
            configs: ExponentialMovingVarianceConfigs,
            state: ExponentialMovingVarianceState
          ) =>
        val updatedMovingAverageState = ExponentialMovingAverage.update(sample)(
          configs.movingAverageConfigs,
          state.movingAverageState
        )
        state.update(
          _.previousValue := state.value,
          _.value := alpha * state.value + beta(sample)(configs, state),
          _.movingAverageState := updatedMovingAverageState
        )
    }
  }
}

object OnBookVolume extends Indicator[OnBookVolumeConfigs, OnBookVolumeState] {
  def update(price: Double, volume: Double)(implicit
      indicatorState: OnBookVolumeState
  ): OnBookVolumeState = {
    val priceDelta = price - indicatorState.previousPrice
    val newValue = if (priceDelta > 0) { indicatorState.value + volume }
    else if (priceDelta < 0) { indicatorState.value - volume }
    else { indicatorState.value }

    indicatorState.update(
      _.value := newValue,
      _.previousValue := indicatorState.value,
      _.previousPrice := price
    )
  }
}

/** Samples a value everytime a bar increments by barSize. Can be used to sample
  * by volume bars, price bars, etc...
  *
  * @param barSize
  * @param value
  * @param previousValue
  * @param barAccumulation
  */
object SampleValueByBarIncrement extends Indicator[SampleValueByBarIncrementConfigs, SampleValueByBarIncrementState] {

  def update(valueUpdate: Double, barIncrement: Double)(implicit
      indicatorConfigs: SampleValueByBarIncrementConfigs,
      indicatorState: SampleValueByBarIncrementState
  ): SampleValueByBarIncrementState = {
    val previousBarNumber =
      (indicatorState.barAccumulation / indicatorConfigs.barSize).toInt
    val barAccumulation = indicatorState.barAccumulation + barIncrement
    val nextBarNumber = (barAccumulation / indicatorConfigs.barSize).toInt
    val updatedValue = if (nextBarNumber > previousBarNumber) { valueUpdate }
    else indicatorState.value
    indicatorState.update(
      _.value := updatedValue,
      _.previousValue := indicatorState.value
    )
  }
}

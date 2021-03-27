package co.firstorderlabs.common.types

import co.firstorderlabs.common.protos.indicators._

object Indicators {
  trait IndicatorConfigs
  trait IndicatorState {
    val value: Double
    val previousValue: Double
  }
  trait IndicatorStateCompanion {
    def create: IndicatorState
  }
  trait MovingIndicatorConfigs extends IndicatorConfigs {
    val windowSize: Int
  }
  trait MovingIndicatorState extends IndicatorState
  trait MovingAverageConfigs extends MovingIndicatorConfigs
  trait MovingAverageState extends MovingIndicatorState
  trait MovingVarianceConfigs extends MovingIndicatorConfigs {
    val movingAverageConfigs: MovingAverageConfigs
    val windowSize = movingAverageConfigs.windowSize
  }
  trait MovingVarianceState extends MovingIndicatorState {
    val movingAverageState: MovingAverageState
  }

  trait ExponentialMovingAverageStateCompanion extends IndicatorStateCompanion {
    override def create: ExponentialMovingAverageState =
      new ExponentialMovingAverageState
  }

  trait ExponentialMovingVarianceStateCompanion
      extends IndicatorStateCompanion {
    override def create: ExponentialMovingVarianceState =
      ExponentialMovingVarianceState(
        movingAverageState = ExponentialMovingAverageState.create
      )
  }

  trait KaufmanAdaptiveMovingAverageStateCompanion
      extends IndicatorStateCompanion {
    override def create: KaufmanAdaptiveMovingAverageState =
      new KaufmanAdaptiveMovingAverageState
  }

  trait KaufmanAdaptiveMovingVarianceStateCompanion
      extends IndicatorStateCompanion {
    override def create: KaufmanAdaptiveMovingVarianceState =
      KaufmanAdaptiveMovingVarianceState(
        movingAverageState = KaufmanAdaptiveMovingAverageState.create
      )
  }

  trait OnBookVolumeStateCompanion
      extends IndicatorStateCompanion {
    override def create: OnBookVolumeState =
      new OnBookVolumeState
  }

  trait SampleValueByBarIncrementStateCompanion extends IndicatorStateCompanion {
    override def create: SampleValueByBarIncrementState =
      new SampleValueByBarIncrementState
  }
}

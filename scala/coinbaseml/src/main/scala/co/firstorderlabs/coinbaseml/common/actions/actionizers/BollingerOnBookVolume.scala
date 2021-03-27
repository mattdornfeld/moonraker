package co.firstorderlabs.coinbaseml.common.actions.actionizers

import co.firstorderlabs.coinbaseml.common.actions.actionizers.Indicators.{KaufmanAdaptiveMovingAverage, KaufmanAdaptiveMovingVariance, OnBookVolume, SampleValueByBarIncrement}
import co.firstorderlabs.coinbaseml.fakebase._
import co.firstorderlabs.common.protos.actionizers.{BollingerOnBookVolumeConfigs, BollingerOnBookVolumeState}
import co.firstorderlabs.common.protos.indicators._

object BollingerOnBookVolume extends ConstantPositionSizeFractionActionizer {
  override val actionizerState = BollingerOnBookVolumeState
  val positionSizeFraction = 1.0

  override def update(actorOutput: Seq[Double])(implicit
      simulationState: SimulationState
  ): Unit = {
    implicit val simulationMetadata = simulationState.simulationMetadata
    implicit val matchingEngineState = simulationState.matchingEngineState

    (
      simulationState.simulationMetadata.actionizerConfigs,
      simulationState.environmentState.actionizerState
    ) match {
      case (
            configs: BollingerOnBookVolumeConfigs,
            state: BollingerOnBookVolumeState
          ) => {
        val midPrice =
          if (simulationMetadata.currentStep > 0)
            MatchingEngine.calcMidPrice
          else 0.0

        val matchVolume = matchingEngineState.matches
          .map(_.quoteVolume.toDouble)
          .reduceOption(_ + _)
          .getOrElse(0.0)

        val updatedPriceMovingVarianceState: KaufmanAdaptiveMovingVarianceState =
          KaufmanAdaptiveMovingVariance.update(midPrice)(
            configs.priceMovingVariance,
            state.priceMovingVariance
          )
        val updatedOnBookVolumeState: OnBookVolumeState =
          OnBookVolume.update(midPrice, matchVolume)(state.onBookVolume)
        val updatedSmoothedOnBookVolumeState: KaufmanAdaptiveMovingAverageState =
          KaufmanAdaptiveMovingAverage.update(updatedOnBookVolumeState.value)(
            configs.smoothedOnBookVolume,
            state.smoothedOnBookVolume
          )
        val updatedSampledOnBookVolumeDerivativeState: SampleValueByBarIncrementState =
          SampleValueByBarIncrement.update(
            KaufmanAdaptiveMovingAverage.derivative()(
              updatedSmoothedOnBookVolumeState
            ),
            matchVolume
          )(
            configs.sampledOnBookVolumeDerivative,
            state.sampledOnBookVolumeDerivative
          )

        val onBookVolumeChangeBuyThreshold =
          configs.onBookVolumeChangeBuyThreshold
        val onBookVolumeChangeSellThreshold =
          configs.onBookVolumeChangeSellThreshold
        val bollingerBandSize = configs.bollingerBandSize

        val smoothedOnBookVolumeChange =
          updatedSampledOnBookVolumeDerivativeState.value
        val (lowerBand, upperBand) =
          KaufmanAdaptiveMovingVariance.bollingerBandValues(bollingerBandSize)(
            updatedPriceMovingVarianceState
          )

        val signal =
          if (
            midPrice < lowerBand && smoothedOnBookVolumeChange < onBookVolumeChangeBuyThreshold
          ) {
            1.0
          } else if (
            midPrice > upperBand && smoothedOnBookVolumeChange > onBookVolumeChangeSellThreshold
          ) {
            -1.0
          } else {
            state.signal
          }

        val updatedActionizerState = state.update(
          _.smoothedOnBookVolume := updatedSmoothedOnBookVolumeState,
          _.priceMovingVariance := updatedPriceMovingVarianceState,
          _.onBookVolume := updatedOnBookVolumeState,
          _.sampledOnBookVolumeDerivative := updatedSampledOnBookVolumeDerivativeState,
          _.signal := signal,
          _.upperBollingerBand := upperBand,
          _.lowerBollingerBand := lowerBand,
          _.smoothedOnBookVolumeChange := smoothedOnBookVolumeChange
        )
        val updatedEnvironmentState = simulationState.environmentState
          .copy(actionizerState = updatedActionizerState)
        val updatedSimulationState =
          simulationState.copy(environmentState = updatedEnvironmentState)

        SimulationState.update(
          simulationMetadata.simulationId,
          updatedSimulationState
        )
      }
    }
  }
}

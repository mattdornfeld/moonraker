package co.firstorderlabs.coinbaseml.common.actions.actionizers

import co.firstorderlabs.coinbaseml.common.actions.actionizers.Actions.{
  Action,
  NoTransaction
}
import co.firstorderlabs.coinbaseml.common.actions.actionizers.BollingerOnBookVolumeState.{
  ActionizerConfigsKeys,
  ActionizerStateKeys
}
import co.firstorderlabs.coinbaseml.common.actions.actionizers.Indicators.{
  KaufmanAdaptiveMovingAverage,
  KaufmanAdaptiveMovingVariance,
  OnBookVolume,
  SampleValueByBarIncrement
}
import co.firstorderlabs.coinbaseml.fakebase._

final case class BollingerOnBookVolumeState(
    bollingerBandSize: Double,
    movingVariance: KaufmanAdaptiveMovingVariance,
    onBookVolume: OnBookVolume,
    smoothedOnBookVolume: KaufmanAdaptiveMovingAverage,
    sampledOnBookVolumeDerivative: SampleValueByBarIncrement,
    var signal: Double
) extends ActionizerState {
  override def getState: Map[String, Double] =
    Map(
      ActionizerStateKeys.lowerBollingerBand -> movingVariance
        .bollingerBandValues(bollingerBandSize)
        ._1,
      ActionizerStateKeys.smoothedOnBookVolumeChange -> sampledOnBookVolumeDerivative.value,
      ActionizerStateKeys.signal -> signal,
      ActionizerStateKeys.upperBollingerBand -> movingVariance
        .bollingerBandValues(bollingerBandSize)
        ._2
    )

  override val companion = BollingerOnBookVolumeState

  override def createSnapshot(implicit
      simulationState: SimulationState
  ): BollingerOnBookVolumeState =
    BollingerOnBookVolumeState(
      bollingerBandSize,
      movingVariance.copy,
      onBookVolume.copy,
      smoothedOnBookVolume.copy,
      sampledOnBookVolumeDerivative.copy,
      signal
    )
}

object BollingerOnBookVolumeState extends ActionizerStateCompanion {
  object ActionizerConfigsKeys {
    val bollingerBandSize = "bollingerBandSize"
    val bollingerBandWindowSize = "bollingerBandWindowSize"
    val onBookVolumeWindowSize = "onBookVolumeWindowSize"
    val onBookVolumeChangeBuyThreshold = "onBookVolumeChangeBuyThreshold"
    val onBookVolumeChangeSellThreshold = "onBookVolumeChangeSellThreshold"
    val volumeBarSize = "volumeBarSize"
  }

  object ActionizerStateKeys {
    val lowerBollingerBand = "lowerBollingerBand"
    val smoothedOnBookVolumeChange = "smoothedOnBookVolumeChange"
    val signal = "signal"
    val upperBollingerBand = "upperBollingerBand"
  }

  override def create(implicit
      simulationMetadata: SimulationMetadata
  ): BollingerOnBookVolumeState = {
    val bollingerBandSize = getConfigValue(
      ActionizerConfigsKeys.bollingerBandSize
    )
    val bollingerBandWindowSize = getConfigValue(
      ActionizerConfigsKeys.bollingerBandWindowSize
    )
    val onBookVolumeWindowSize = getConfigValue(
      ActionizerConfigsKeys.onBookVolumeWindowSize
    )
    val volumeBarSize = getConfigValue(ActionizerConfigsKeys.volumeBarSize)

    BollingerOnBookVolumeState(
      bollingerBandSize,
      KaufmanAdaptiveMovingVariance(bollingerBandWindowSize.toInt),
      new OnBookVolume,
      KaufmanAdaptiveMovingAverage(onBookVolumeWindowSize.toInt),
      SampleValueByBarIncrement(volumeBarSize),
      0.0
    )
  }

  override def fromSnapshot(snapshot: ActionizerState): ActionizerState =
    snapshot match {
      case snapshot: BollingerOnBookVolumeState =>
        BollingerOnBookVolumeState(
          snapshot.bollingerBandSize,
          snapshot.movingVariance.copy,
          snapshot.onBookVolume.copy,
          snapshot.smoothedOnBookVolume.copy,
          snapshot.sampledOnBookVolumeDerivative.copy,
          snapshot.signal
        )
    }
}

object BollingerOnBookVolume extends Actionizer with PositionRebalancer {
  override val actionizerState = BollingerOnBookVolumeState
  val positionSizeFraction = 0.1

  private def calcSmoothedOnBookVolumeDerivative(
      actionizerState: BollingerOnBookVolumeState,
      matchingEngineState: MatchingEngineState,
      midPrice: Double
  ): Double = {
    val onBookVolume = actionizerState.onBookVolume
    val smoothedOnBookVolume = actionizerState.smoothedOnBookVolume
    val sampledOnBookVolumeDerivative =
      actionizerState.sampledOnBookVolumeDerivative

    val matchVolume = matchingEngineState.matches
      .map(_.quoteVolume.toDouble)
      .reduceOption(_ + _)
      .getOrElse(0.0)

    onBookVolume.update(midPrice, matchVolume)
    smoothedOnBookVolume.update(onBookVolume.value)
    sampledOnBookVolumeDerivative.update(
      smoothedOnBookVolume.derivative(),
      matchVolume
    )
    sampledOnBookVolumeDerivative.value
  }

  override def construct(actorOutput: Seq[Double])(implicit
      simulationState: SimulationState
  ): Action = {
    implicit val simulationMetadata = simulationState.simulationMetadata
    implicit val matchingEngineState = simulationState.matchingEngineState
    implicit val walletsState = simulationState.accountState.walletsState

    simulationState.environmentState.actionizerState match {
      case actionizerState: BollingerOnBookVolumeState => {
        val midPrice =
          if (simulationMetadata.currentStep > 0)
            MatchingEngine.calcMidPrice
          else 0.0

        val movingVariance = actionizerState.movingVariance
        movingVariance.update(midPrice)

        val onBookVolumeChangeBuyThreshold =
          BollingerOnBookVolumeState.getConfigValue(
            ActionizerConfigsKeys.onBookVolumeChangeBuyThreshold
          )
        val onBookVolumeChangeSellThreshold =
          BollingerOnBookVolumeState.getConfigValue(
            ActionizerConfigsKeys.onBookVolumeChangeSellThreshold
          )
        val smoothedOnBookVolumeChange = calcSmoothedOnBookVolumeDerivative(
          actionizerState,
          matchingEngineState,
          midPrice
        )

        val bandSize = BollingerOnBookVolumeState.getConfigValue(
          ActionizerConfigsKeys.bollingerBandSize
        )
        val (lowerBand, upperBand) =
          movingVariance.bollingerBandValues(bandSize)

        if (
          midPrice < lowerBand && smoothedOnBookVolumeChange < onBookVolumeChangeBuyThreshold
        ) {
          actionizerState.signal = 1.0
          updateOpenPositions(positionSizeFraction)
        } else if (
          midPrice > upperBand && smoothedOnBookVolumeChange > onBookVolumeChangeSellThreshold
        ) {
          actionizerState.signal = -1.0
          closeAllOpenPositions
        } else {
          new NoTransaction
        }

      }
    }
  }
}

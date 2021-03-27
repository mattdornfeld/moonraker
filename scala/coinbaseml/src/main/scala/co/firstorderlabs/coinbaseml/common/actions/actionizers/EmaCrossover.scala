package co.firstorderlabs.coinbaseml.common.actions.actionizers

import co.firstorderlabs.coinbaseml.common.actions.actionizers.Indicators.ExponentialMovingAverage
import co.firstorderlabs.coinbaseml.fakebase.{MatchingEngine, SimulationState}
import co.firstorderlabs.common.protos.actionizers.{EmaCrossOverConfigs, EmaCrossOverState}

object EmaCrossOver extends ConstantPositionSizeFractionActionizer {
  val positionSizeFraction = 0.1
  override val actionizerState = EmaCrossOverState

  override def update(
      actorOutput: Seq[Double]
  )(implicit simulationState: SimulationState): Unit = {
    implicit val matchingEngineState = simulationState.matchingEngineState
    implicit val simulationMetadata = simulationState.simulationMetadata

    (
      simulationState.simulationMetadata.actionizerConfigs,
      simulationState.environmentState.actionizerState
    ) match {
      case (configs: EmaCrossOverConfigs, state: EmaCrossOverState) => {
        val midPrice =
          if (simulationMetadata.currentStep > 0)
            MatchingEngine.calcMidPrice(simulationState.matchingEngineState)
          else 0.0

        val updatedEmaFast = ExponentialMovingAverage
          .update(midPrice)(configs.emaFast, state.emaFast)
        val updatedEmaSlow = ExponentialMovingAverage
          .update(midPrice)(configs.emaSlow, state.emaSlow)

        val updatedSignal =
          if (
            simulationMetadata.isWarmedUp && ExponentialMovingAverage
              .crossAbove(updatedEmaFast, updatedEmaSlow)
          ) {
            1.0
          } else if (
            simulationMetadata.isWarmedUp && ExponentialMovingAverage
              .crossBelow(updatedEmaFast, updatedEmaSlow)
          ) {
            -1.0
          } else {
            state.signal
          }

        val updatedState = state.update(
          _.emaFast := updatedEmaFast,
          _.emaSlow := updatedEmaSlow,
          _.signal := updatedSignal
        )

        updateActionizerState(updatedState)
      }
    }
  }
}

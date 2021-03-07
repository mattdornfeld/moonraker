package co.firstorderlabs.coinbaseml.common.actions.actionizers

import co.firstorderlabs.coinbaseml.common.actions.actionizers.Actions.{Action, NoTransaction}
import co.firstorderlabs.coinbaseml.common.actions.actionizers.Indicators.ExponentialMovingAverage
import co.firstorderlabs.coinbaseml.fakebase.{MatchingEngine, SimulationMetadata, SimulationState}

final case class EmaCrossOverState(
    emaFast: ExponentialMovingAverage,
    emaSlow: ExponentialMovingAverage
) extends ActionizerState {
  override val companion = EmaCrossOverState
  import companion.ActionizerStateKeys

  override def createSnapshot(implicit
      simulationState: SimulationState
  ): EmaCrossOverState =
    simulationState.environmentState.actionizerState match {
      case emaCrossOverState: EmaCrossOverState =>
        EmaCrossOverState(
          emaCrossOverState.emaFast.copy,
          emaCrossOverState.emaSlow.copy
        )
    }

  override def getState: Map[String, Double] =
    Map(
      ActionizerStateKeys.emaFast -> emaFast.value,
      ActionizerStateKeys.emaSlow -> emaSlow.value
    )
}

object EmaCrossOverState extends ActionizerStateCompanion {
  object ActionizerConfigsKeys {
    val fastWindowSize = "fastWindowSize"
    val slowWindowSize = "slowWindowSize"
  }

  object ActionizerStateKeys {
    val emaFast = "emaFast"
    val emaSlow = "emaSlow"
  }

  override def create(implicit
      simulationMetadata: SimulationMetadata
  ): EmaCrossOverState = {
    val fastWindowSize = getConfigValue(ActionizerConfigsKeys.fastWindowSize)
    val slowWindowSize = getConfigValue(ActionizerConfigsKeys.slowWindowSize)
    EmaCrossOverState(
      ExponentialMovingAverage(2 / fastWindowSize, 0.0, 0.0),
      ExponentialMovingAverage(2 / slowWindowSize, 0.0, 0.0)
    )
  }

  override def fromSnapshot(snapshot: ActionizerState): ActionizerState =
    snapshot match {
      case snapshot: EmaCrossOverState =>
        EmaCrossOverState(snapshot.emaFast.copy, snapshot.emaSlow.copy)
    }

}

object EmaCrossOver extends Actionizer with PositionRebalancer {
  val positionSizeFraction = 0.1
  override val actionizerState = EmaCrossOverState

  override def construct(
      actorOutput: Seq[Double]
  )(implicit simulationState: SimulationState): Action = {
    val updateOnly = actorOutput.size > 0 && actorOutput(0) == -1
    implicit val matchingEngineState = simulationState.matchingEngineState
    implicit val simulationMetadata = simulationState.simulationMetadata
    implicit val walletState = simulationState.accountState.walletsState

    simulationState.environmentState.actionizerState match {
      case actionizerState: EmaCrossOverState => {
        val midPrice =
          if (simulationMetadata.currentStep > 0)
            MatchingEngine.calcMidPrice(simulationState.matchingEngineState)
          else 0.0

        List(actionizerState.emaFast, actionizerState.emaSlow)
          .map { ema => ema.update(midPrice) }

        if (
          simulationMetadata.isWarmedUp && !updateOnly && actionizerState.emaFast
            .crossAbove(actionizerState.emaSlow)
        ) {
          updateOpenPositions(positionSizeFraction)
        } else if (
          simulationMetadata.isWarmedUp && !updateOnly && actionizerState.emaFast
            .crossBelow(actionizerState.emaSlow)
        ) {
          closeAllOpenPositions
        } else {
          new NoTransaction
        }
      }
    }
  }
}

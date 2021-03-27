package co.firstorderlabs.coinbaseml.common.actions.actionizers

import co.firstorderlabs.coinbaseml.common.actions.actionizers.Actions.Action
import co.firstorderlabs.coinbaseml.common.utils.Utils.DoubleUtils
import co.firstorderlabs.coinbaseml.fakebase.SimulationState
import co.firstorderlabs.common.protos.actionizers.PositionSizeState

/**
  * Generates Actions from an actorOutput of size 1. The entry is interpreted to be the percentage of the portfolio
  * that should be made up of the product.
  */
object PositionSize extends Actionizer with PositionRebalancer {
  override val actionizerState = PositionSizeState

  override def construct(implicit
      simulationState: SimulationState
  ): Action = {
    implicit val matchingEngineState = simulationState.matchingEngineState
    implicit val simulationMetadata = simulationState.simulationMetadata
    implicit val walletState = simulationState.accountState.walletsState
    simulationState.environmentState.actionizerState match {
      case state: PositionSizeState =>
        updateOpenPositions(state.positionSizeFraction)
    }

  }

  override def update(
      actorOutput: Seq[Double]
  )(implicit simulationState: SimulationState): Unit = {
    require(actorOutput.size == 1)

    val positionSizeFraction = actorOutput(0).clamp(0, 1)
    val updatedSignal = if (positionSizeFraction > 0) 1.0 else -1.0
    val updatedState = simulationState.environmentState.actionizerState match {
      case state: PositionSizeState =>
        state.update(
          _.signal := updatedSignal,
          _.positionSizeFraction := positionSizeFraction,
          _.actorOutput := actorOutput
        )
    }
    updateActionizerState(updatedState)
  }

}

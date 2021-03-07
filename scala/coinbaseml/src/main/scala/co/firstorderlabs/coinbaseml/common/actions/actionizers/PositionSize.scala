package co.firstorderlabs.coinbaseml.common.actions.actionizers

import co.firstorderlabs.coinbaseml.common.utils.Utils.DoubleUtils
import co.firstorderlabs.coinbaseml.fakebase.SimulationState


/**
  * Generates Actions from an actorOutput of size 1. The entry is interpreted to be the percentage of the portfolio
  * that should be made up of the product.
  */
object PositionSize
    extends Actionizer
    with PositionRebalancer
    with StatelessActionizer {
  override def construct(
      actorOutput: Seq[Double]
  )(implicit simulationState: SimulationState): Actions.Action = {
    implicit val matchingEngineState = simulationState.matchingEngineState
    implicit val simulationMetadata = simulationState.simulationMetadata
    implicit val walletState = simulationState.accountState.walletsState
    require(actorOutput.size == 1)
    val positionSizeFraction = actorOutput(0).clamp(0, 1)
    updateOpenPositions(positionSizeFraction)
  }
}

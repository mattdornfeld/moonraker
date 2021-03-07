package co.firstorderlabs.coinbaseml.common.actions.actionizers

import co.firstorderlabs.coinbaseml.common.actions.actionizers.Actions.Action
import co.firstorderlabs.coinbaseml.fakebase.SimulationState

object EntrySignal
    extends Actionizer
    with PositionRebalancer
    with StatelessActionizer {
  val validEntrySignalValues = List(0, 1)
  val positionSizeFraction = 0.1
  override def construct(
      actorOutput: Seq[Double]
  )(implicit simulationState: SimulationState): Action = {
    implicit val matchingEngineState = simulationState.matchingEngineState
    implicit val simulationMetadata = simulationState.simulationMetadata
    implicit val walletState = simulationState.accountState.walletsState
    require(actorOutput.size == 1)
    val entrySignal = actorOutput(0).toInt
    require(validEntrySignalValues.contains(entrySignal))
    if (entrySignal == 0) {
      closeAllOpenPositions
    } else if (entrySignal == 1) {
      updateOpenPositions(positionSizeFraction)
    } else {
      throw new IllegalArgumentException
    }
  }
}

package co.firstorderlabs.coinbaseml.common.actions.actionizers

import co.firstorderlabs.coinbaseml.common.utils.Utils.DoubleUtils
import co.firstorderlabs.coinbaseml.fakebase.SimulationState
import co.firstorderlabs.common.protos.actionizers.EntrySignalState

object EntrySignal extends ConstantPositionSizeFractionActionizer {
  override val actionizerState = EntrySignalState
  val validEntrySignalValues = List(0, 1)
  val positionSizeFraction = 0.1

  override def update(
      actorOutput: Seq[Double]
  )(implicit simulationState: SimulationState): Unit = {
    implicit val matchingEngineState = simulationState.matchingEngineState
    implicit val simulationMetadata = simulationState.simulationMetadata
    implicit val walletState = simulationState.accountState.walletsState

    require(actorOutput.size == 1)
    val rawSignal = actorOutput(0)
    require(validEntrySignalValues.contains(rawSignal))

    val signal = if (rawSignal === 0.0) { -1.0 }
    else if (rawSignal === 1.0) { 1.0 }
    else { throw new IllegalArgumentException }

    val updatedActionizerState =
      simulationState.environmentState.actionizerState match {
        case state: EntrySignalState =>
          state.update(
            _.signal := signal,
            _.actorOutput := actorOutput
          )
      }

    updateActionizerState(updatedActionizerState)
  }
}

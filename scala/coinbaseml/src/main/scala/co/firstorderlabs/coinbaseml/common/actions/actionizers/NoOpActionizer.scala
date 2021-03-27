package co.firstorderlabs.coinbaseml.common.actions.actionizers

import co.firstorderlabs.coinbaseml.common.actions.actionizers.Actions.{
  Action,
  NoTransaction
}
import co.firstorderlabs.coinbaseml.fakebase.SimulationState
import co.firstorderlabs.common.protos.actionizers.NoOpActionizerState

object NoOpActionizer extends Actionizer {
  override val actionizerState = NoOpActionizerState

  override def construct(implicit simulationState: SimulationState): Action =
    new NoTransaction

  override def update(actorOutput: Seq[Double])(implicit
      simulationState: SimulationState
  ): Unit = {}
}

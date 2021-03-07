package co.firstorderlabs.coinbaseml.common.actions.actionizers

import co.firstorderlabs.coinbaseml.common.actions.actionizers.Actions.{Action, NoTransaction}
import co.firstorderlabs.coinbaseml.fakebase.SimulationState

object NoOpActionizer extends Actionizer with StatelessActionizer {
  override def construct(
      actorOutput: Seq[Double]
  )(implicit simulationState: SimulationState): Action =
    new NoTransaction
}

package co.firstorderlabs.coinbaseml.common.actions.actionizers

import co.firstorderlabs.coinbaseml.common.actions.actionizers.Actions.Action

trait Actionizer {
  def construct(actorOutput: Seq[Double]): Action
}

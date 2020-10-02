package co.firstorderlabs.common.actions.actionizers

import co.firstorderlabs.common.actions.actionizers.Actions.Action

trait Actionizer {
  def construct(actorOutput: Seq[Double]): Action
}

package co.firstorderlabs.coinbaseml.common.actions.actionizers

import co.firstorderlabs.coinbaseml.fakebase.{
  SimulationMetadata,
  SimulationState
}

final class Stateless extends ActionizerState {
  override val companion = Stateless

  override def equals(obj: Any): Boolean =
    obj match {
      case _: Stateless => true
      case _            => false
    }

  override def createSnapshot(implicit
      simulationState: SimulationState
  ): Stateless = new Stateless

  override def getState: Map[String, Double] = Map()
}

object Stateless extends ActionizerStateCompanion {
  override def create(implicit
      simulationMetadata: SimulationMetadata
  ): Stateless =
    new Stateless

  def getState: Map[String, Double] = Map()

  override def fromSnapshot(snapshot: ActionizerState): ActionizerState =
    new Stateless
}

trait StatelessActionizer {
  val actionizerState = Stateless
}

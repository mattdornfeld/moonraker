package co.firstorderlabs.coinbaseml.common.actions.actionizers

import co.firstorderlabs.coinbaseml.common.actions.actionizers.Actions.Action
import co.firstorderlabs.coinbaseml.common.types.Exceptions.UnrecognizedActionizer
import co.firstorderlabs.coinbaseml.fakebase.{
  SimulationMetadata,
  SimulationState,
  State,
  StateCompanion
}
import co.firstorderlabs.common.protos.environment.{
  Actionizer => ActionizerProto
}

trait ActionizerState extends State[ActionizerState] {
  def getState: Map[String, Double]
}

trait ActionizerStateCompanion extends StateCompanion[ActionizerState] {
  def getConfigValue(
      key: String
  )(implicit simulationMetadata: SimulationMetadata): Double = {
    require(
      simulationMetadata.actionizerConfigs.contains(key),
      s"$key must be specified in actionizerConfigs"
    )

    simulationMetadata.actionizerConfigs(key)
  }
}

trait Actionizer {
  val actionizerState: ActionizerStateCompanion
  def construct(
      actorOutput: Seq[Double]
  )(implicit simulationState: SimulationState): Action
}

object Actionizer {
  def fromProto(actionizer: ActionizerProto): Actionizer = {
    actionizer match {
      case ActionizerProto.NoOpActionizer        => NoOpActionizer
      case ActionizerProto.SignalPositionSize    => SignalPositionSize
      case ActionizerProto.PositionSize          => PositionSize
      case ActionizerProto.EntrySignal           => EntrySignal
      case ActionizerProto.EmaCrossOver          => EmaCrossOver
      case ActionizerProto.BollingerOnBookVolume => BollingerOnBookVolume
      case _                                     => throw new UnrecognizedActionizer
    }
  }
}

package co.firstorderlabs.coinbaseml.common.actions.actionizers

import co.firstorderlabs.coinbaseml.common.actions.actionizers.Actions.{Action, NoTransaction}
import co.firstorderlabs.coinbaseml.common.actions.actionizers.Indicators.ExponentialMovingAverage
import co.firstorderlabs.coinbaseml.common.types.Exceptions.UnrecognizedActionizer
import co.firstorderlabs.coinbaseml.common.utils.Utils.Interval.IntervalType
import co.firstorderlabs.coinbaseml.common.utils.Utils.{DoubleUtils, Interval}
import co.firstorderlabs.coinbaseml.fakebase._
import co.firstorderlabs.common.protos.environment.{Actionizer => ActionizerProto}

trait ActionizerState extends State[ActionizerState] {
  def getState: Map[String, Double]
}
trait ActionizerStateCompanion extends StateCompanion[ActionizerState]

sealed trait Actionizer {
  val actionizerState: ActionizerStateCompanion
  def construct(
      actorOutput: Seq[Double]
  )(implicit simulationState: SimulationState): Action
}

object Actionizer {
  def fromProto(actionizer: ActionizerProto): Actionizer = {
    actionizer match {
      case ActionizerProto.NoOpActionizer     => NoOpActionizer
      case ActionizerProto.SignalPositionSize => SignalPositionSize
      case ActionizerProto.PositionSize       => PositionSize
      case ActionizerProto.EntrySignal        => EntrySignal
      case ActionizerProto.EmaCrossOver       => EmaCrossOver
      case _                                  => throw new UnrecognizedActionizer
    }
  }
}

class Stateless extends ActionizerState {
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

object NoOpActionizer extends Actionizer with StatelessActionizer {
  override def construct(
      actorOutput: Seq[Double]
  )(implicit simulationState: SimulationState): Action =
    new NoTransaction
}

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

/**
  * Generates actions from an actorOutput vector of length 2. The first element of the vector is interpreted as the
  * entrySignal. The second element is interpreted as the positionSizeFraction. Both are doubles between 0.0 and 1.0.
  * This Actionizer processes the actorOutput according to the following rules:
  *   - If entrySignal==0.0 all open positions are closed with a SellMarketOrder
  *   - If 0.0 < entrySignal < 1.0 no action is taken
  *   - If entrySignal==1.0 the portfolio is rebalanced using a MarketOrderEvent so that positionSizeFraction of the
  *     portfolio consists of ProductCurrency
  */
object SignalPositionSize
    extends Actionizer
    with PositionRebalancer
    with StatelessActionizer {
  val minimumValueDifferentialFraction = 0.05
  val closeAllPositionsRange = Interval(0, 0.333, IntervalType.closed)
  val noTransactionRange = Interval(0.333, 0.667)
  val openNewPositionRange = Interval(0.667, 1)

  override def construct(
      actorOutput: Seq[Double]
  )(implicit simulationState: SimulationState): Action = {
    implicit val matchingEngineState = simulationState.matchingEngineState
    implicit val simulationMetadata = simulationState.simulationMetadata
    implicit val walletState = simulationState.accountState.walletsState
    require(
      actorOutput.size == 2,
      s"$actorOutput {actorOutput} has size ${actorOutput.size}. Must be 2."
    )
    val entrySignal = actorOutput.head.clamp(0, 1)
    val positionSizeFraction = actorOutput(1).clamp(0, 1)

    if (openNewPositionRange.contains(entrySignal)) {
      updateOpenPositions(positionSizeFraction)
    } else if (closeAllPositionsRange.contains(entrySignal)) {
      closeAllOpenPositions
    } else {
      new NoTransaction
    }
  }
}

case class EmaCrossOverState(
    emaFast: ExponentialMovingAverage,
    emaSlow: ExponentialMovingAverage
) extends ActionizerState {
  override val companion = EmaCrossOverState

  override def createSnapshot(implicit
      simulationState: SimulationState
  ): EmaCrossOverState = {
    simulationState.environmentState.actionizerState match {
      case emaCrossOverState: EmaCrossOverState =>
        EmaCrossOverState(
          emaCrossOverState.emaFast.copy,
          emaCrossOverState.emaSlow.copy
        )
    }
  }

  override def getState: Map[String, Double] =
    Map(
      "emaFast" -> emaFast.value,
      "emaSlow" -> emaSlow.value
    )
}

object EmaCrossOverState extends ActionizerStateCompanion {
  override def create(implicit
      simulationMetadata: SimulationMetadata
  ): EmaCrossOverState = {
    require(
      simulationMetadata.actionizerConfigs.contains("fastWindowSize"),
      "fastWindowSize must be specified in actionizerConfigs"
    )
    require(
      simulationMetadata.actionizerConfigs.contains("slowWindowSize"),
      "slowWindowSize must be specified in actionizerConfigs"
    )
    val fastWindowSize =
      simulationMetadata
        .actionizerConfigs("fastWindowSize")
    val slowWindowSize =
      simulationMetadata
        .actionizerConfigs("slowWindowSize")
    EmaCrossOverState(
      ExponentialMovingAverage(2 / fastWindowSize, 0.0, 0.0),
      ExponentialMovingAverage(2 / slowWindowSize, 0.0, 0.0)
    )
  }

  override def fromSnapshot(snapshot: ActionizerState): ActionizerState =
    snapshot match {
      case snapshot: EmaCrossOverState =>
        EmaCrossOverState(snapshot.emaFast.copy, snapshot.emaSlow.copy)
    }

}

object EmaCrossOver extends Actionizer with PositionRebalancer {
  val positionSizeFraction = 0.1
  override val actionizerState = EmaCrossOverState

  override def construct(
      actorOutput: Seq[Double]
  )(implicit simulationState: SimulationState): Action = {
    val updateOnly = actorOutput.size > 0 && actorOutput(0) == -1
    implicit val matchingEngineState = simulationState.matchingEngineState
    implicit val simulationMetadata = simulationState.simulationMetadata
    implicit val walletState = simulationState.accountState.walletsState

    simulationState.environmentState.actionizerState match {
      case actionizerState: EmaCrossOverState => {
        val midPrice =
          if (simulationMetadata.currentStep > 0)
            MatchingEngine.calcMidPrice(simulationState.matchingEngineState)
          else 0.0

        List(actionizerState.emaFast, actionizerState.emaSlow)
          .map{ema => ema.update(midPrice)}

        if (
          simulationMetadata.isWarmedUp && !updateOnly && actionizerState.emaFast
            .crossAbove(actionizerState.emaSlow)
        ) {
          updateOpenPositions(positionSizeFraction)
        } else if (
          simulationMetadata.isWarmedUp && !updateOnly && actionizerState.emaFast
            .crossBelow(actionizerState.emaSlow)
        ) {
          closeAllOpenPositions
        } else {
          new NoTransaction
        }
      }
    }
  }
}

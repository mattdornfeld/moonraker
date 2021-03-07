package co.firstorderlabs.coinbaseml.common.actions.actionizers

import co.firstorderlabs.coinbaseml.common.actions.actionizers.Actions.{
  Action,
  NoTransaction
}
import co.firstorderlabs.coinbaseml.common.utils.Utils.{DoubleUtils, Interval}
import co.firstorderlabs.coinbaseml.common.utils.Utils.Interval.IntervalType
import co.firstorderlabs.coinbaseml.fakebase.SimulationState

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

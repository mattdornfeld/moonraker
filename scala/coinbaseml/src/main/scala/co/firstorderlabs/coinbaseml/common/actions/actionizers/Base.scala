package co.firstorderlabs.coinbaseml.common.actions.actionizers

import co.firstorderlabs.coinbaseml.common.actions.actionizers.Actions.{
  Action,
  BuyMarketOrderTransaction,
  NoTransaction,
  SellMarketOrderTransaction
}
import co.firstorderlabs.coinbaseml.common.actions.actionizers.SignalPositionSize.minimumValueDifferentialFraction
import co.firstorderlabs.coinbaseml.common.rewards.ReturnRewardStrategy
import co.firstorderlabs.coinbaseml.common.types.Exceptions.UnrecognizedActionizer
import co.firstorderlabs.coinbaseml.common.utils.Utils.DoubleUtils
import co.firstorderlabs.coinbaseml.fakebase._
import co.firstorderlabs.common.currency.Price.BtcUsdPrice.{
  ProductVolume,
  QuoteVolume
}
import co.firstorderlabs.common.protos.actionizers.{
  Actionizer => ActionizerProto
}
import co.firstorderlabs.common.protos.events.Liquidity
import co.firstorderlabs.common.types.Actionizers.{
  ActionizerState,
  ActionizerStateCompanion
}

trait Actionizer {
  val actionizerState: ActionizerStateCompanion

  def construct(implicit simulationState: SimulationState): Action

  def update(
      actorOutput: Seq[Double]
  )(implicit simulationState: SimulationState): Unit

  def updateAndConstruct(
      actorOutput: Seq[Double]
  )(implicit simulationState: SimulationState): Action = {
    update(actorOutput)
    val updatedSimulationState =
      SimulationState.getOrFail(simulationState.simulationMetadata.simulationId)
    construct(updatedSimulationState)
  }

  protected def updateActionizerState(
      updatedActionizerState: ActionizerState
  )(implicit simulationState: SimulationState): Unit = {
    val updatedEnvironmentState = simulationState.environmentState
      .copy(actionizerState = updatedActionizerState)
    val updatedSimulationState =
      simulationState.copy(environmentState = updatedEnvironmentState)
    SimulationState.update(
      simulationState.simulationMetadata.simulationId,
      updatedSimulationState
    )
  }
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

trait PositionRebalancer {
  private def getCurrentPositionValue(implicit
      matchingEngineState: MatchingEngineState,
      walletState: WalletsState
  ): Double =
    ReturnRewardStrategy.currentPortfolioValue - Wallets
      .getAvailableFunds(QuoteVolume)
      .toDouble

  private def getMinRebalanceMagnitude(implicit
      matchingEngineState: MatchingEngineState
  ): Double =
    ReturnRewardStrategy.currentPortfolioValue * minimumValueDifferentialFraction

  private def getNewPositionValue(
      positionSizeFraction: Double
  )(implicit matchingEngineState: MatchingEngineState): Double =
    ReturnRewardStrategy.currentPortfolioValue * positionSizeFraction

  protected def closeAllOpenPositions(implicit
      simulationMetadata: SimulationMetadata,
      walletState: WalletsState
  ): Action = {
    val availableProduct =
      Wallets.getAvailableFunds(ProductVolume).asInstanceOf[ProductVolume]
    if (availableProduct.isZero) {
      new NoTransaction
    } else {
      SellMarketOrderTransaction(
        availableProduct,
        simulationMetadata.simulationId
      )
    }
  }

  protected def updateOpenPositions(positionSizeFraction: Double)(implicit
      matchingEngineState: MatchingEngineState,
      simulationMetadata: SimulationMetadata,
      walletState: WalletsState
  ): Action = {
    val currentPositionValue = getCurrentPositionValue
    val newPositionValue = getNewPositionValue(positionSizeFraction)
    val rebalanceDirection =
      Math.signum(newPositionValue - currentPositionValue).toInt
    val minRebalanceMagnitude = getMinRebalanceMagnitude
    val rebalanceMagnitude = Math.abs(newPositionValue - currentPositionValue)

    if (rebalanceDirection == 1) {
      val rebalanceFunds =
        if (rebalanceMagnitude > minRebalanceMagnitude)
          new QuoteVolume(Right(rebalanceMagnitude.toString))
            .subtractFees(Liquidity.taker)
        else QuoteVolume.zeroVolume

      if (rebalanceFunds.isZero) new NoTransaction
      else
        BuyMarketOrderTransaction(
          rebalanceFunds,
          simulationMetadata.simulationId
        )

    } else if (rebalanceDirection == -1) {
      val rebalanceSize =
        if (rebalanceMagnitude > minRebalanceMagnitude)
          new ProductVolume(
            Right(
              (rebalanceMagnitude / MatchingEngine.calcMidPrice).toString
            )
          )
        else ProductVolume.zeroVolume

      if (rebalanceSize.isZero) new NoTransaction
      else
        SellMarketOrderTransaction(
          rebalanceSize,
          simulationMetadata.simulationId
        )

    } else {
      new NoTransaction
    }
  }
}

trait ConstantPositionSizeFractionActionizer
    extends Actionizer
    with PositionRebalancer {
  val positionSizeFraction: Double

  override def construct(implicit simulationState: SimulationState): Action = {
    implicit val simulationMetadata = simulationState.simulationMetadata
    implicit val matchingEngineState = simulationState.matchingEngineState
    implicit val walletsState = simulationState.accountState.walletsState
    val actionizerState = simulationState.environmentState.actionizerState

    if (actionizerState.signal === 1.0) {
      updateOpenPositions(positionSizeFraction)
    } else if (actionizerState.signal === -1.0) {
      closeAllOpenPositions
    } else {
      new NoTransaction
    }
  }

}

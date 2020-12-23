package co.firstorderlabs.coinbaseml.common.actions.actionizers

import co.firstorderlabs.coinbaseml.common.actions.actionizers.Actions.{Action, BuyMarketOrderTransaction, NoTransaction, SellMarketOrderTransaction}
import co.firstorderlabs.coinbaseml.common.actions.actionizers.SignalPositionSize.minimumValueDifferentialFraction
import co.firstorderlabs.coinbaseml.common.rewards.ReturnRewardStrategy
import co.firstorderlabs.coinbaseml.fakebase.{MatchingEngine, Wallets}
import co.firstorderlabs.common.currency.Price.BtcUsdPrice.{ProductVolume, QuoteVolume}
import co.firstorderlabs.common.protos.events.Liquidity

trait PositionRebalancer {
  private def getCurrentPositionValue: Double =
    ReturnRewardStrategy.currentPortfolioValue - Wallets
      .getAvailableFunds(QuoteVolume)
      .toDouble

  private def getMinRebalanceMagnitude: Double =
    ReturnRewardStrategy.currentPortfolioValue * minimumValueDifferentialFraction

  private def getNewPositionValue(positionSizeFraction: Double): Double =
    ReturnRewardStrategy.currentPortfolioValue * positionSizeFraction

  protected def updateOpenPositions(positionSizeFraction: Double): Action = {
    val currentPositionValue = getCurrentPositionValue
    val newPositionValue = getNewPositionValue(positionSizeFraction)
    val rebalanceDirection =
      Math.signum(newPositionValue - currentPositionValue).toInt
    val minRebalanceMagnitude = getMinRebalanceMagnitude
    val rebalanceMagnitude = Math.abs(newPositionValue - currentPositionValue)

    if (rebalanceDirection == 1) {
      val rebalanceFunds =
        if (rebalanceMagnitude > minRebalanceMagnitude)
          new QuoteVolume(Right(rebalanceMagnitude.toString)).subtractFees(Liquidity.taker)
        else QuoteVolume.zeroVolume

      if (rebalanceFunds.isZero) new NoTransaction
      else BuyMarketOrderTransaction(rebalanceFunds)

    } else if (rebalanceDirection == -1) {
      val rebalanceSize = if (rebalanceMagnitude > minRebalanceMagnitude)
        new ProductVolume(
          Right(
            (rebalanceMagnitude / MatchingEngine.calcMidPrice).toString
          )
        )
      else ProductVolume.zeroVolume

      if (rebalanceSize.isZero) new NoTransaction
      else SellMarketOrderTransaction(rebalanceSize)

    } else {
      new NoTransaction
    }
  }
}

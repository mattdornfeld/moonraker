package co.firstorderlabs.coinbaseml.common.actions.actionizers

import co.firstorderlabs.coinbaseml.common.actions.actionizers.Actions.{Action, BuyMarketOrderTransaction, NoTransaction, SellMarketOrderTransaction}
import co.firstorderlabs.coinbaseml.common.rewards.ReturnRewardStrategy
import co.firstorderlabs.coinbaseml.common.utils.Utils.DoubleEquality
import co.firstorderlabs.coinbaseml.fakebase.Wallets
import co.firstorderlabs.common.currency.Configs.ProductPrice.{ProductVolume, QuoteVolume}
import co.firstorderlabs.common.protos.events.Liquidity

/**
  * Generates actions from an actorOutput vector of length 2. The first element of the vector is interpreted as the
  * entrySignal. The second element is interpreted as the positionSizeFraction. Both are doubles between 0.0 and 1.0.
  * This Actionizer processes the actorOutput according to the following rules:
  *   - If entrySignal==0.0 all open positions are closed with a SellMarketOrder
  *   - If 0.0 < entrySignal < 1.0 no action is taken
  *   - If entrySignal==1.0 the portfolio is rebalanced using a MarketOrderEvent so that positionSizeFraction of the
  *     portfolio consists of ProductCurrency
  */
object SignalPositionSize extends Actionizer {
  val minimumValueDifferentialFraction = 0.05

  private def closeAllOpenPositions: Action = {
    val availableProduct =
      Wallets.getAvailableFunds(ProductVolume).asInstanceOf[ProductVolume]
    if (availableProduct.isZero) {
      new NoTransaction
    } else {
      SellMarketOrderTransaction(availableProduct)
    }
  }

  private def getCurrentPositionValue: Double =
    ReturnRewardStrategy.currentPortfolioValue - Wallets
      .getAvailableFunds(QuoteVolume)
      .toDouble

  private def getMinRebalanceMagnitude: Double =
    ReturnRewardStrategy.currentPortfolioValue * minimumValueDifferentialFraction

  private def getNewPositionValue(positionSizeFraction: Double): Double =
    ReturnRewardStrategy.currentPortfolioValue * positionSizeFraction

  private def updateOpenPositions(positionSizeFraction: Double): Action = {
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
            (rebalanceMagnitude / ReturnRewardStrategy.currentMidPrice).toString
          )
        )
      else ProductVolume.zeroVolume

      if (rebalanceSize.isZero) new NoTransaction
      else SellMarketOrderTransaction(rebalanceSize)

    } else {
      new NoTransaction
    }
  }

  override def construct(actorOutput: Seq[Double]): Action = {
    require(actorOutput.size == 2)
    val entrySignal = actorOutput.head
    val positionSizeFraction = actorOutput(1)
    require(entrySignal >= 0.0 && entrySignal <= 1.0)
    require(positionSizeFraction >= 0.0 && positionSizeFraction <= 1.0)

    if (entrySignal === 1.0) {
      updateOpenPositions(positionSizeFraction)
    } else if (entrySignal === 0.0) {
      closeAllOpenPositions
    } else {
      new NoTransaction
    }
  }
}

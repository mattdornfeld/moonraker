package co.firstorderlabs.common.rewards

import co.firstorderlabs.common.utils.Utils.logEpsilon
import co.firstorderlabs.fakebase.Exchange

/** Calculates reward as log(currentPortfolioValue / previousPortfolioValue)
  */
object LogReturnRewardStrategy extends RewardStrategy {
  override def calcReward: Double = {
    val currentPortfolioValue = calcPortfolioValue(
      Exchange.getSimulationMetadata.currentTimeInterval
    )
    val previousPortfolioValue = calcPortfolioValue(
      Exchange.getSimulationMetadata.previousTimeInterval
    )

    logEpsilon(currentPortfolioValue / previousPortfolioValue)
  }
}

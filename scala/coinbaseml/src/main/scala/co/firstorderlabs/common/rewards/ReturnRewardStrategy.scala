package co.firstorderlabs.common.rewards

import co.firstorderlabs.fakebase.Exchange

/** Calculates reward as currentPortfolioValue - previousPortfolioValue
  */
object ReturnRewardStrategy extends RewardStrategy {
  override def calcReward: Double = {
    calcPortfolioValue(Exchange.getSimulationMetadata.currentTimeInterval) - calcPortfolioValue(
      Exchange.getSimulationMetadata.previousTimeInterval
    )
  }
}

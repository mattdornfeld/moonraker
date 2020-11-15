package co.firstorderlabs.coinbaseml.common.rewards

import co.firstorderlabs.coinbaseml.common.utils.Utils.logEpsilon
import co.firstorderlabs.coinbaseml.fakebase.Exchange

/** Calculates reward as log(currentPortfolioValue / previousPortfolioValue)
  */
object LogReturnRewardStrategy extends RewardStrategy {
  override def calcReward: Double = {
    if (Exchange.getSimulationMetadata.currentStep >= 2) {
      logEpsilon(currentPortfolioValue / (previousPortfolioValue + 1e-10))
    } else {
      0.0
    }
  }
}

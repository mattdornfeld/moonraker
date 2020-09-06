package co.firstorderlabs.common.rewards

import co.firstorderlabs.common.utils.Utils.logEpsilon
import co.firstorderlabs.fakebase.SnapshotBuffer

/** Calculates reward as log(currentPortfolioValue / previousPortfolioValue)
  */
object LogReturnRewardStrategy extends RewardStrategy {
  override def calcReward: Double = {
    if (SnapshotBuffer.size >= 2) {
      logEpsilon(currentPortfolioValue / (previousPortfolioValue + 1e-10))
    } else {
      0.0
    }
  }
}

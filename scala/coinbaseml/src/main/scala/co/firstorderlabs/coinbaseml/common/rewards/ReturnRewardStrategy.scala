package co.firstorderlabs.coinbaseml.common.rewards

import co.firstorderlabs.coinbaseml.fakebase.SnapshotBuffer

/** Calculates reward as currentPortfolioValue - previousPortfolioValue
  */
object ReturnRewardStrategy extends RewardStrategy {
  override def calcReward: Double = {
    if (SnapshotBuffer.size >= 2) {
      currentPortfolioValue - previousPortfolioValue
    } else {
      0.0
    }
  }
}

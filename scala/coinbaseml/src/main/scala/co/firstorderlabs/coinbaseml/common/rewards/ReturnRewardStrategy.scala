package co.firstorderlabs.coinbaseml.common.rewards

import co.firstorderlabs.coinbaseml.fakebase.SnapshotBuffer

/** Calculates reward as currentPortfolioValue - previousPortfolioValue
  */
object ReturnRewardStrategy extends RewardStrategy {
  override def calcReward: Double = {
    if (SnapshotBuffer.size >= 2) {
      val _formerPreviousPortfolioValue = previousPortfolioValue
      val _currentPortfolioValue = currentPortfolioValue
      _previousPortfolioValue = Some(_currentPortfolioValue)
      _currentPortfolioValue - _formerPreviousPortfolioValue
    } else {
      0.0
    }
  }
}

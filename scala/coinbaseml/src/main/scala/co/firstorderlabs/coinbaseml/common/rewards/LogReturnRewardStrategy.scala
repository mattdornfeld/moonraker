package co.firstorderlabs.coinbaseml.common.rewards

import co.firstorderlabs.coinbaseml.common.utils.Utils.logEpsilon
import co.firstorderlabs.coinbaseml.fakebase.{Exchange, SnapshotBuffer}

/** Calculates reward as log(currentPortfolioValue / previousPortfolioValue)
  */
object LogReturnRewardStrategy extends RewardStrategy {
  override def calcReward: Double = {
    if (
      SnapshotBuffer
        .getSnapshotOrElse(Exchange.getSimulationMetadata.previousTimeInterval)
        .nonEmpty
    ) {
      logEpsilon(currentPortfolioValue / (previousPortfolioValue + 1e-10))
    } else {
      0.0
    }
  }
}

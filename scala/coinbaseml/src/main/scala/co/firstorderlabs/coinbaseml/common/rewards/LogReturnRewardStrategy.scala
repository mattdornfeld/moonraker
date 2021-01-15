package co.firstorderlabs.coinbaseml.common.rewards

import co.firstorderlabs.coinbaseml.common.utils.Utils.logEpsilon
import co.firstorderlabs.coinbaseml.fakebase.SimulationState

/** Calculates reward as log(currentPortfolioValue / previousPortfolioValue)
  */
object LogReturnRewardStrategy extends RewardStrategy {
  override def calcReward(implicit simulationState: SimulationState): Double = {
    implicit val matchingEngineState = simulationState.matchingEngineState
    if (simulationState.simulationMetadata.currentStep >= 2) {
      logEpsilon(currentPortfolioValue / (previousPortfolioValue + 1e-10))
    } else {
      0.0
    }
  }
}

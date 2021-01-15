package co.firstorderlabs.coinbaseml.common.rewards

import co.firstorderlabs.coinbaseml.fakebase.SimulationState

/** Calculates reward as currentPortfolioValue - previousPortfolioValue
  */
object ReturnRewardStrategy extends RewardStrategy {
  override def calcReward(implicit simulationState: SimulationState): Double = {
    implicit val matchingEngineState = simulationState.matchingEngineState
    if (simulationState.simulationMetadata.currentStep >= 2) {
      val _formerPreviousPortfolioValue = previousPortfolioValue
      val _currentPortfolioValue = currentPortfolioValue
      _previousPortfolioValue = Some(_currentPortfolioValue)
      _currentPortfolioValue - _formerPreviousPortfolioValue
    } else {
      0.0
    }
  }
}

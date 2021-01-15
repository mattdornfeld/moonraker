package co.firstorderlabs.coinbaseml.common.rewards

import co.firstorderlabs.coinbaseml.fakebase.{MatchingEngine, MatchingEngineState, SimulationState}

/** RewardStrategy is a base trait for all reward strategies
  */
trait RewardStrategy {
  protected var _previousPortfolioValue: Option[Double] = None

  /** Calculates mid price for the current TimeInterval
    *
    * @return
    */
  def calcMidPrice(implicit matchingEngineState: MatchingEngineState): Double = MatchingEngine.calcMidPrice

  /** All RewardStrategy objects must implement this method
    *
    * @return
    */
  def calcReward(implicit simulationState: SimulationState): Double

  def currentPortfolioValue(implicit matchingEngineState: MatchingEngineState): Double = matchingEngineState.currentPortfolioValue match {
    case Some(currentPortfolioValue) => currentPortfolioValue
    case None => throw new IllegalStateException
  }

  protected def previousPortfolioValue(implicit matchingEngineState: MatchingEngineState): Double = matchingEngineState.previousPortfolioValue match {
    case Some(previousPortfolioValue) => previousPortfolioValue
    case None => throw new IllegalStateException
  }
}

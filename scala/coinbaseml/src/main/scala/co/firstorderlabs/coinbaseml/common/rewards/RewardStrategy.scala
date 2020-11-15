package co.firstorderlabs.coinbaseml.common.rewards

import co.firstorderlabs.coinbaseml.fakebase.MatchingEngine

/** RewardStrategy is a base trait for all reward strategies
  */
trait RewardStrategy {
  protected var _previousPortfolioValue: Option[Double] = None

  /** Calculates mid price for the current TimeInterval
    *
    * @return
    */
  def calcMidPrice: Double = MatchingEngine.calcMidPrice

  /** All RewardStrategy objects must implement this method
    *
    * @return
    */
  def calcReward: Double

  def currentPortfolioValue: Double = MatchingEngine.currentPortfolioValue match {
    case Some(currentPortfolioValue) => currentPortfolioValue
    case None => throw new IllegalStateException
  }

  protected def previousPortfolioValue: Double = MatchingEngine.previousPortfolioValue match {
    case Some(previousPortfolioValue) => previousPortfolioValue
    case None => throw new IllegalStateException
  }
}

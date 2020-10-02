package co.firstorderlabs.common.rewards

import java.util.logging.Logger

import co.firstorderlabs.fakebase.currency.Configs.ProductPrice
import co.firstorderlabs.fakebase.currency.Configs.ProductPrice.{
  ProductVolume,
  QuoteVolume
}
import co.firstorderlabs.fakebase.types.Types.TimeInterval
import co.firstorderlabs.fakebase.{Exchange, OrderBook, SnapshotBuffer}

/** RewardStrategy is a base trait for all reward strategies
  */
trait RewardStrategy {
  private val logger = Logger.getLogger(this.toString)

  /** Calculates mid price for a given TimeInterval
    *
    * @param timeInterval
    * @return
    */
  def calcMidPrice(timeInterval: TimeInterval): Double = {
    val snapshot = SnapshotBuffer.getSnapshot(timeInterval)
    val buyOrderBook = new OrderBook(
      Some(snapshot.matchingEngineSnapshot.buyOrderBookSnapshot)
    )
    val sellOrderBook = new OrderBook(
      Some(snapshot.matchingEngineSnapshot.sellOrderBookSnapshot)
    )

    val bestAskPrice = sellOrderBook.minPrice.getOrElse {
      logger.warning(
        "The best ask price is 0. This indicates the sell order book is empty"
      )
      ProductPrice.zeroPrice
    }
    val bestBidPrice = buyOrderBook.maxPrice.getOrElse {
      logger.warning(
        "The best bid price is 0. This indicates the buy order book is empty"
      )
      ProductPrice.zeroPrice
    }

    ((bestAskPrice + bestBidPrice) / Right(2.0)).toDouble
  }

  /** Calculates portfolio value for a given TimeInterval
    *
    * @param timeInterval
    * @return
    */
  def calcPortfolioValue(timeInterval: TimeInterval): Double = {
    val snapshot = SnapshotBuffer.getSnapshot(timeInterval)

    val productVolume = snapshot.accountSnapshot.walletsSnapshot.walletsMap
      .get(ProductVolume.currency)
      .get
      .balance
      .toDouble
    val quoteVolume = snapshot.accountSnapshot.walletsSnapshot.walletsMap
      .get(QuoteVolume.currency)
      .get
      .balance
      .toDouble

    productVolume * calcMidPrice(timeInterval) + quoteVolume
  }

  /** All RewardStrategy objects must implement this method
    *
    * @return
    */
  def calcReward: Double

  def currentPortfolioValue: Double =
    calcPortfolioValue(Exchange.getSimulationMetadata.currentTimeInterval)

  def currentMidPrice: Double =
  calcMidPrice(Exchange.getSimulationMetadata.currentTimeInterval)

  protected def previousPortfolioValue: Double =
    calcPortfolioValue(Exchange.getSimulationMetadata.previousTimeInterval)
}

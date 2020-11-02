package co.firstorderlabs.coinbaseml.common.rewards

import java.util.logging.Logger

import co.firstorderlabs.common.currency.Configs.ProductPrice
import co.firstorderlabs.common.currency.Configs.ProductPrice.{ProductVolume, QuoteVolume}
import co.firstorderlabs.common.protos.fakebase.OrderSide
import co.firstorderlabs.coinbaseml.fakebase.types.Types.TimeInterval
import co.firstorderlabs.coinbaseml.fakebase._

/** RewardStrategy is a base trait for all reward strategies
  */
trait RewardStrategy {
  private val logger = Logger.getLogger(this.toString)

  private def currentTimeInterval: TimeInterval =
    Exchange.getSimulationMetadata.currentTimeInterval

  /** Calculates mid price for a given TimeInterval
    *
    * @param timeInterval
    * @return
    */
  def calcMidPrice(timeInterval: TimeInterval): Double = {
    val (buyOrderBook, sellOrderBook) =
      if (timeInterval == currentTimeInterval) {
        (
          Exchange.getOrderBook(OrderSide.buy),
          Exchange.getOrderBook(OrderSide.sell)
        )
      } else {
        val snapshot = SnapshotBuffer.getSnapshot(timeInterval)
        (
          new OrderBook(
            Some(snapshot.matchingEngineSnapshot.buyOrderBookSnapshot)
          ),
          new OrderBook(
            Some(snapshot.matchingEngineSnapshot.sellOrderBookSnapshot)
          )
        )
      }

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
    val (productWallet, quoteWallet) =
      if (timeInterval == currentTimeInterval) {
        (Wallets.getWallet(ProductVolume), Wallets.getWallet(QuoteVolume))
      } else {
        val walletsMap = SnapshotBuffer
          .getSnapshot(timeInterval)
          .accountSnapshot
          .walletsSnapshot
          .walletsMap

        (
          walletsMap(ProductVolume.currency)
            .asInstanceOf[Wallet[ProductVolume]],
          walletsMap(QuoteVolume.currency).asInstanceOf[Wallet[QuoteVolume]]
        )
      }

    val productVolume = productWallet.balance.toDouble
    val quoteVolume = quoteWallet.balance.toDouble

    productVolume * calcMidPrice(timeInterval) + quoteVolume
  }

  /** All RewardStrategy objects must implement this method
    *
    * @return
    */
  def calcReward: Double

  def currentPortfolioValue: Double =
    calcPortfolioValue(currentTimeInterval)

  def currentMidPrice: Double =
    calcMidPrice(currentTimeInterval)

  protected def previousPortfolioValue: Double =
    calcPortfolioValue(Exchange.getSimulationMetadata.previousTimeInterval)
}

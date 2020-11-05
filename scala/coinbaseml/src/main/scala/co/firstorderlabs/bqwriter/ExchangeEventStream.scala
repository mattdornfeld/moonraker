package co.firstorderlabs.bqwriter

import info.bitrich.xchangestream.coinbasepro.{
  CoinbaseProStreamingExchange,
  CoinbaseProStreamingMarketDataService
}
import info.bitrich.xchangestream.coinbasepro.dto.CoinbaseProWebSocketTransaction
import info.bitrich.xchangestream.core.{ProductSubscription, StreamingExchange}
import io.reactivex.{Completable, Observable}
import org.knowm.xchange.currency.CurrencyPair

case class CoinbaseStreamingEventService(
    coinbaseProStreamingMarketDataService: CoinbaseProStreamingMarketDataService
) {
  def getEvents(
      currencyPair: CurrencyPair
  ): Observable[CoinbaseProWebSocketTransaction] = {
    coinbaseProStreamingMarketDataService
      .getRawWebSocketTransactions(currencyPair, false)
  }
}

class CoinbaseProStreamingEventExchange extends CoinbaseProStreamingExchange {
  private var streamingEventService: Option[CoinbaseStreamingEventService] =
    None

  override def connect(args: ProductSubscription*): Completable = {
    val _exchangeSpecification = getDefaultExchangeSpecification
    _exchangeSpecification.setExchangeSpecificParametersItem(
      StreamingExchange.L3_ORDERBOOK,
      true
    )
    applySpecification(_exchangeSpecification)
    val completable = super.connect(args: _*)
    streamingEventService = Some(
      CoinbaseStreamingEventService(getStreamingMarketDataService)
    )
    completable
  }

  @throws[IllegalStateException]
  def getStreamingEventService: CoinbaseStreamingEventService = {
    streamingEventService match {
      case Some(streamingEventService) => streamingEventService
      case None                        => throw new IllegalStateException
    }
  }
}

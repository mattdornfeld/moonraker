package co.firstorderlabs.bqwriter

import info.bitrich.xchangestream.coinbasepro.dto.CoinbaseProWebSocketTransaction
import info.bitrich.xchangestream.core.{
  ProductSubscription,
  StreamingExchangeFactory
}
import io.reactivex.Observable

object WriterApp extends App {
  val exchange = StreamingExchangeFactory.INSTANCE
    .createExchange(classOf[CoinbaseProStreamingEventExchange])
    .asInstanceOf[CoinbaseProStreamingEventExchange]

  val productSubscription = ProductSubscription.create
    .addAll(Configs.channelId)
    .build

  exchange.connect(productSubscription).blockingAwait

  val events: Observable[CoinbaseProWebSocketTransaction] =
    exchange.getStreamingEventService.getEvents(Configs.channelId)

  events.blockingSubscribe(new EventObserver)
}

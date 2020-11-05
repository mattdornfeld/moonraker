package co.firstorderlabs.coinbaseml.fakebase.sql

import java.math.BigDecimal
import java.time.Duration
import java.util.concurrent.{LinkedBlockingQueue => LinkedBlockingQueueBase}

import co.firstorderlabs.coinbaseml.fakebase.Snapshot
import co.firstorderlabs.coinbaseml.fakebase.protos.fakebase.{BuyLimitOrderRequest, BuyMarketOrderRequest}
import co.firstorderlabs.common.currency.Configs.ProductPrice
import co.firstorderlabs.common.currency.Configs.ProductPrice.ProductVolume
import co.firstorderlabs.common.currency.Price.BtcUsdPrice.QuoteVolume
import co.firstorderlabs.common.protos.events.{BuyLimitOrder, BuyMarketOrder, Cancellation, DoneReason, MatchEvents, OrderSide, OrderStatus, RejectReason, SellLimitOrder, SellMarketOrder}
import co.firstorderlabs.common.types.Types.{OrderId, OrderRequestId, ProductId, TimeInterval}
import doobie.implicits.legacy.instant.JavaTimeInstantMeta
import doobie.util.meta.Meta

import scala.collection.concurrent.TrieMap
import scala.collection.mutable

case class DatabaseReaderSnapshot(
    timeIntervalQueue: LinkedBlockingQueueBase[TimeInterval]
) extends Snapshot

case class QueryResult(
    buyLimitOrders: List[BuyLimitOrder],
    buyMarketOrders: List[BuyMarketOrder],
    cancellations: List[Cancellation],
    sellLimitOrders: List[SellLimitOrder],
    sellMarketOrder: List[SellMarketOrder],
    timeInterval: TimeInterval
) {
  def chunkByTimeDelta(timeDelta: Duration): List[QueryResult] = {
    val groupedBuyLimitOrders = buyLimitOrders.groupBy(x =>
      timeInterval.getSubInterval(x.time, timeDelta)
    )
    val groupedBuyMarketOrders = buyMarketOrders.groupBy(x =>
      timeInterval.getSubInterval(x.time, timeDelta)
    )
    val groupedCancellations =
      cancellations.groupBy(x => timeInterval.getSubInterval(x.time, timeDelta))
    val groupedSellLimitOrders = sellLimitOrders.groupBy(x =>
      timeInterval.getSubInterval(x.time, timeDelta)
    )
    val groupedSellMarketOrders = sellMarketOrder.groupBy(x =>
      timeInterval.getSubInterval(x.time, timeDelta)
    )

    timeInterval.chunkByTimeDelta(timeDelta).map { timeInterval =>
      QueryResult(
        groupedBuyLimitOrders.getOrElse(timeInterval, List()),
        groupedBuyMarketOrders.getOrElse(timeInterval, List()),
        groupedCancellations.getOrElse(timeInterval, List()),
        groupedSellLimitOrders.getOrElse(timeInterval, List()),
        groupedSellMarketOrders.getOrElse(timeInterval, List()),
        timeInterval
      )
    }
  }
}

final case class BoundedTrieMap[K, V](
    var maxSize: Int,
    trieMap: Option[TrieMap[K, V]] = None
) extends mutable.AbstractMap[K, V] {
  val _trieMap = if (trieMap.isEmpty) new TrieMap[K, V] else trieMap.get

  override def addOne(elem: (K, V)): BoundedTrieMap.this.type = {
    _trieMap.addOne(elem)
    this
  }

  override def clear: Unit = _trieMap.clear

  override def isEmpty: Boolean = _trieMap.isEmpty

  override def remove(key: K): Option[V] = _trieMap.remove(key)

  def iterator: Iterator[(K, V)] = {
    _trieMap.iterator
  }

  override def put(key: K, value: V): Option[V] = {
    if (size >= maxSize) {
      None
    } else {
      _trieMap.put(key, value)
    }
  }

  override def size: Int = _trieMap.size

  override def clone: BoundedTrieMap[K, V] = {
    BoundedTrieMap(maxSize, Some(_trieMap.clone))
  }

  override def get(key: K): Option[V] = _trieMap.get(key)

  override def subtractOne(elem: K): BoundedTrieMap.this.type = {
    _trieMap.subtractOne(elem)
    this
  }
}

class LinkedBlockingQueue[A] extends LinkedBlockingQueueBase[A] {
  override def equals(that: Any): Boolean = {
    that match {
      case that: LinkedBlockingQueue[A] =>
        this.toArray.toList == that.toArray.toList
      case _ => false
    }
  }

  def takeOrElse(default: Option[A] = None): Option[A] =
    if (isEmpty) { default }
    else { Some(super.take()) }
}

object Implicits {
  // Specify how to convert from JDBC supported data types to custom data types
  implicit val javaTimeInstantMeta = JavaTimeInstantMeta
  implicit val doneReasonConverter: Meta[DoneReason] =
    Meta[Int].timap(value => DoneReason.fromValue(value))(value => value.value)
  implicit val durationConverter: Meta[Duration] =
    Meta[Long].timap(value => Duration.ofNanos(value))(value => value.toNanos)
  implicit val matchEvents: Meta[MatchEvents] =
    Meta[String].timap(_ => MatchEvents())(_ => "")
  implicit val orderIdConverter: Meta[OrderId] =
    Meta[String].timap(value => OrderId(value))(value => value.orderId)
  implicit val orderRequestIdConverter: Meta[OrderRequestId] = Meta[String]
    .timap(value => OrderRequestId(value))(value => value.orderRequestId)
  implicit val orderSideConverter: Meta[OrderSide] =
    Meta[String].timap(value => OrderSide.fromName(value).get)(value =>
      value.name
    )
  implicit val orderStatusConverter: Meta[OrderStatus] =
    Meta[String].timap(value => OrderStatus.fromName(value).get)(value =>
      value.name
    )
  implicit val productIdConverter: Meta[ProductId] =
    Meta[String].timap(value => ProductId.fromString(value))(value =>
      value.toString
    )
  implicit val productPriceConverter: Meta[ProductPrice] = Meta[BigDecimal]
    .timap(value => new ProductPrice(Left(value)))(value => value.amount)
  implicit val productVolumeConverter: Meta[ProductVolume] = Meta[BigDecimal]
    .timap(value => new ProductVolume(Left(value)))(value => value.amount)
  implicit val quoteVolumeConverter: Meta[QuoteVolume] =
    Meta[BigDecimal].timap(value => new QuoteVolume(Left(value)))(value =>
      value.amount
    )
  implicit val rejectReasonConverter: Meta[RejectReason] =
    Meta[Int].timap(value => RejectReason.fromValue(value))(value =>
      value.value
    )

  implicit val buyLimitOrderRequestConverter: Meta[BuyLimitOrderRequest] =
    Meta[String].timap(_ => new BuyLimitOrderRequest)(_ => "")
  implicit val buyMarketOrderRequestConverter: Meta[BuyMarketOrderRequest] =
    Meta[String].timap(_ => new BuyMarketOrderRequest())(_ => "")
}

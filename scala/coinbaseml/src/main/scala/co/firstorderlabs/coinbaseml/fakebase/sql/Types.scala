package co.firstorderlabs.coinbaseml.fakebase.sql

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, ObjectInputStream, ObjectOutputStream}
import java.math.BigDecimal
import java.time.Duration
import java.util.concurrent.{LinkedBlockingQueue => LinkedBlockingQueueBase}

import co.firstorderlabs.coinbaseml.fakebase.Snapshot
import co.firstorderlabs.common.currency.Configs.ProductPrice
import co.firstorderlabs.common.currency.Configs.ProductPrice.{ProductVolume, QuoteVolume}
import co.firstorderlabs.common.protos.events.{DoneReason, MatchEvents, OrderSide, OrderStatus, RejectReason}
import co.firstorderlabs.common.protos.fakebase.{BuyLimitOrderRequest, BuyMarketOrderRequest}
import co.firstorderlabs.common.types.Events.Event
import co.firstorderlabs.common.types.Types.{OrderId, OrderRequestId, ProductId, TimeInterval}
import doobie.implicits.legacy.instant.JavaTimeInstantMeta
import doobie.util.meta.Meta

import scala.collection.concurrent.TrieMap
import scala.collection.mutable

final case class DatabaseReaderSnapshot(
    timeInterval: TimeInterval
) extends Snapshot

final case class QueryResult(
    events: List[Event],
    timeInterval: TimeInterval
) {
  def chunkByTimeDelta(timeDelta: Duration): List[QueryResult] = {
    val groupedEvents = events.groupBy(event =>
      timeInterval.getSubInterval(event.time, timeDelta)
    )

    timeInterval.chunkBy(timeDelta).map { timeInterval =>
      QueryResult(
        groupedEvents.getOrElse(timeInterval, List()),
        timeInterval
      )
    }
  }

  override def toString: String = s"<QueryResult(${timeInterval})"
}

trait Deserializer[A] {
  private type resultType = A
  def deserialize(bytes: Array[Byte]): A = {
    val objectInputStream = new ObjectInputStream(
      new ByteArrayInputStream(bytes)
    )
    val value = objectInputStream.readObject.asInstanceOf[resultType]
    objectInputStream.close
    value
  }
}

object QueryResult extends Deserializer[QueryResult]


case class BoundedTrieMap[K, V](
    var maxSize: Int,
    trieMap: Option[TrieMap[K, V]] = None
) extends mutable.AbstractMap[K, V] {
  protected val _trieMap = if (trieMap.isEmpty) new TrieMap[K, V] else trieMap.get

  override def addOne(elem: (K, V)): BoundedTrieMap.this.type = {
    _trieMap.addOne(elem)
    this
  }

  override def clear: Unit = _trieMap.clear

  override def isEmpty: Boolean = _trieMap.isEmpty

  def isFull: Boolean = _trieMap.size >= maxSize

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

  override def size: Int = synchronized{_trieMap.size}

  override def clone: BoundedTrieMap[K, V] = {
    BoundedTrieMap(maxSize, Some(_trieMap.clone))
  }

  override def get(key: K): Option[V] = _trieMap.get(key)

  override def subtractOne(elem: K): BoundedTrieMap.this.type = {
    _trieMap.subtractOne(elem)
    this
  }
}

final class LinkedBlockingQueue[A] extends LinkedBlockingQueueBase[A] {
  override def equals(that: Any): Boolean = {
    that match {
      case that: LinkedBlockingQueue[A] =>
        this.toArray.toList == that.toArray.toList
      case _ => false
    }
  }

  def takeOrElse(default: Option[A] = None): Option[A] = {
    if (toArray.isEmpty) { default }
    else { Some(super.take) }
  }
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

  implicit class Serializer(obj: Object) {
    def serialize: Array[Byte] = {
      val stream = new ByteArrayOutputStream()
      val objectOutputStream = new ObjectOutputStream(stream)
      objectOutputStream.writeObject(obj)
      objectOutputStream.close
      stream.toByteArray
    }
  }
}
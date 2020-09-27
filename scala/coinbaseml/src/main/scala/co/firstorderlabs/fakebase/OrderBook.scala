package co.firstorderlabs.fakebase

import java.time.{Duration, Instant}

import co.firstorderlabs.common.utils.Utils.When
import co.firstorderlabs.fakebase.currency.Configs.ProductPrice
import co.firstorderlabs.fakebase.currency.Configs.ProductPrice.ProductVolume
import co.firstorderlabs.fakebase.protos.fakebase._
import co.firstorderlabs.fakebase.types.Events.LimitOrderEvent
import co.firstorderlabs.fakebase.types.Exceptions.OrderBookEmpty
import co.firstorderlabs.fakebase.types.Types.OrderId

import scala.annotation.tailrec
import scala.collection.mutable.{HashMap, TreeMap}
import scala.math.Ordering

class AggregatedMap extends HashMap[ProductPrice, ProductVolume] {
  override def apply(key: ProductPrice): ProductVolume =
    super.getOrElseUpdate(key, ProductVolume.zeroVolume)
}

case class OrderBookSnapshot(
    orderIdLookup: HashMap[OrderId, LimitOrderEvent],
    priceTimeTree: TreeMap[OrderBookKey, LimitOrderEvent]
) extends Snapshot

case class OrderBookKey(price: ProductPrice, time: Duration, degeneracy: Int)

class OrderBook(snapshot: Option[OrderBookSnapshot] = None)
    extends Snapshotable[OrderBookSnapshot] {
  private val orderIdLookup = new HashMap[OrderId, LimitOrderEvent]
  private val priceTimeTree =
    new TreeMap[OrderBookKey, LimitOrderEvent]()(OrderBook.OrderBookKeyOrdering)

  if (snapshot.isDefined) {
    restore(snapshot.get)
  }

  override def createSnapshot: OrderBookSnapshot = {
    OrderBookSnapshot(orderIdLookup.clone, priceTimeTree.clone)
  }

  override def restore(snapshot: OrderBookSnapshot): Unit = {
    clear
    orderIdLookup.addAll(snapshot.orderIdLookup.iterator)
    priceTimeTree.addAll(snapshot.priceTimeTree.iterator)
  }

  override def clear: Unit = {
    orderIdLookup.clear
    priceTimeTree.clear
  }

  override def isCleared: Boolean = {
    orderIdLookup.isEmpty && priceTimeTree.isEmpty
  }

  def aggregateToMap(
      depth: Int,
      fromTop: Boolean = false
  ): Map[ProductPrice, ProductVolume] = {
    val priceTimeTreeIterator =
      priceTimeTree.whenElse(fromTop)(_.toList.reverseIterator, _.iterator)
    val aggregatedMap = new AggregatedMap

    @tailrec
    def populateAggregatedMap: Unit = {
      if (!priceTimeTreeIterator.hasNext) return

      val (_, limitOrder) = priceTimeTreeIterator.next()
      if (
        aggregatedMap.size >= depth && !aggregatedMap.contains(limitOrder.price)
      ) {} else {
        aggregatedMap(limitOrder.price) += limitOrder.size
        populateAggregatedMap
      }
    }

    populateAggregatedMap
    aggregatedMap.toMap
  }

  def getOrderByOrderBookKey(
      orderBookKey: OrderBookKey
  ): Option[LimitOrderEvent] = priceTimeTree.get(orderBookKey)

  def getOrderByOrderId(orderId: OrderId): Option[LimitOrderEvent] =
    orderIdLookup.get(orderId)

  def isEmpty: Boolean = {
    orderIdLookup.isEmpty
  }

  def iterator: Iterator[(OrderBookKey, LimitOrderEvent)] = {
    priceTimeTree.iterator
  }

  def maxOrder: Option[LimitOrderEvent] = {
    priceTimeTree
      .maxOption(OrderBook.OrderBookKeyValueOrdering)
      .collect { case item: (OrderBookKey, LimitOrderEvent) => item._2 }
  }

  def maxPrice: Option[ProductPrice] = {
    priceTimeTree
      .maxOption(OrderBook.OrderBookKeyValueOrdering)
      .collect { case item: (OrderBookKey, LimitOrderEvent) => item._1.price }
  }

  def minOrder: Option[LimitOrderEvent] = {
    priceTimeTree
      .minOption(OrderBook.OrderBookKeyValueOrdering)
      .collect { case item: (OrderBookKey, LimitOrderEvent) => item._2 }
  }

  def minPrice: Option[ProductPrice] = {
    priceTimeTree
      .minOption(OrderBook.OrderBookKeyValueOrdering)
      .collect { case item: (OrderBookKey, LimitOrderEvent) => item._1.price }
  }

  def removeByKey(key: OrderBookKey): Option[LimitOrderEvent] = {
    val order = priceTimeTree.remove(key)
    orderIdLookup.remove(order.get.orderId)
  }

  def removeByOrderId(orderId: OrderId): Option[LimitOrderEvent] = {
    val order = orderIdLookup.remove(orderId)
    val key = OrderBook.getOrderBookKey(order.get)
    priceTimeTree.remove(key)
  }

  @throws[OrderBookEmpty]
  def throwExceptionIfEmpty: Unit = {
    if (isEmpty) {
      throw new OrderBookEmpty
    }
  }

  def update(key: OrderBookKey, order: LimitOrderEvent) = {
    require(
      order.orderStatus.isopen,
      "can only add open orders to the order book"
    )
    priceTimeTree.update(key, order)
    orderIdLookup.update(order.orderId, order)
  }
}

object OrderBook {
  implicit val OrderBookKeyOrdering = new Ordering[OrderBookKey] {
    override def compare(a: OrderBookKey, b: OrderBookKey): Int = {
      (a.price, a.time, a.degeneracy) compare (b.price, b.time, b.degeneracy)
    }
  }

  implicit val OrderBookKeyValueOrdering =
    new Ordering[(OrderBookKey, LimitOrderEvent)] {
      override def compare(
          a: (OrderBookKey, LimitOrderEvent),
          b: (OrderBookKey, LimitOrderEvent)
      ): Int = {
        (
          a._1.price,
          a._1.time,
          a._1.degeneracy
        ) compare (b._1.price, b._1.time, b._1.degeneracy)
      }
    }

  def getOrderBookKey(order: LimitOrderEvent): OrderBookKey = {
    order.side match {
      case OrderSide.buy =>
        OrderBookKey(
          order.price,
          Duration.between(Instant.MAX, order.time),
          order.degeneracy
        )
      case OrderSide.sell =>
        OrderBookKey(
          order.price,
          Duration.between(order.time, Instant.MIN),
          order.degeneracy
        )
    }
  }
}

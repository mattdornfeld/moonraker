package co.firstorderlabs.fakebase

import java.time.{Duration, Instant}

import co.firstorderlabs.fakebase.currency.Configs.ProductPrice
import co.firstorderlabs.fakebase.currency.Configs.ProductPrice.ProductVolume
import co.firstorderlabs.fakebase.protos.fakebase._
import co.firstorderlabs.fakebase.types.Events.LimitOrderEvent
import co.firstorderlabs.fakebase.types.Types.OrderId

import scala.collection.mutable.{HashMap, TreeMap}
import scala.math.Ordering

case class OrderBookCheckpoint(
  orderIdLookup: HashMap[OrderId, LimitOrderEvent],
  priceTimeTree: TreeMap[OrderBookKey, LimitOrderEvent]
) extends Checkpoint

case class OrderBookKey(price: ProductPrice, time: Duration, degeneracy: Int)

class OrderBook extends Checkpointable[OrderBookCheckpoint] {
  private val orderIdLookup = new HashMap[OrderId, LimitOrderEvent]
  private val priceTimeTree =
    new TreeMap[OrderBookKey, LimitOrderEvent]()(OrderBook.OrderBookKeyOrdering)

  override def checkpoint: OrderBookCheckpoint = {
    OrderBookCheckpoint(orderIdLookup.clone, priceTimeTree.clone)
  }

  override def restore(checkpoint: OrderBookCheckpoint): Unit = {
    clear
    orderIdLookup.addAll(checkpoint.orderIdLookup.iterator)
    priceTimeTree.addAll(checkpoint.priceTimeTree.iterator)
  }

  override def clear: Unit = {
    orderIdLookup.clear
    priceTimeTree.clear
  }

  override def isCleared: Boolean = {
    orderIdLookup.isEmpty && priceTimeTree.isEmpty
  }

  private def getPriceAtDepth(depth: Int, fromTop: Boolean): ProductPrice = {
    val distinctPrices = priceTimeTree.toList.distinctBy(item => item._2.price)

    if (distinctPrices.size >= depth)
      if (fromTop) distinctPrices(distinctPrices.size - depth)._2.price
      else distinctPrices(depth)._2.price
    else if (fromTop) ProductPrice.zeroPrice
    else ProductPrice.maxPrice
  }

  def aggregateToMap(
    depth: Int,
    fromTop: Boolean = false
  ): Map[ProductPrice, ProductVolume] = {
    def filterItemsBeyondPriceDepth(
      item: (OrderBookKey, LimitOrderEvent)
    ): Boolean = {
      val priceAtDepth = getPriceAtDepth(depth, fromTop)
      if (fromTop) item._2.price >= priceAtDepth
      else item._2.price < priceAtDepth
    }

    priceTimeTree
      .filter(filterItemsBeyondPriceDepth)
      .groupMapReduce[ProductPrice, ProductVolume](item => item._2.price)(
        item => item._2.size
      )(_ + _)
  }

  def getOrderByOrderBookKey(
    orderBookKey: OrderBookKey
  ): Option[LimitOrderEvent] = priceTimeTree.get(orderBookKey)

  def getOrderByOrderId(orderId: OrderId): Option[LimitOrderEvent] =
    orderIdLookup.get(orderId)

  def isEmpty: Boolean = {
    orderIdLookup.isEmpty
  }

  def iterator: Iterator[(OrderBookKey, LimitOrderEvent)]= {
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
      override def compare(a: (OrderBookKey, LimitOrderEvent),
                           b: (OrderBookKey, LimitOrderEvent)): Int = {
        (a._1.price, a._1.time, a._1.degeneracy) compare (b._1.price, b._1.time, b._1.degeneracy)
      }
    }

  def getOrderBookKey(order: LimitOrderEvent): OrderBookKey = {
    order.side match {
      case OrderSide.buy =>
        OrderBookKey(
          order.price,
          Duration.between(Instant.MAX, order.time.instant),
          order.degeneracy
        )
      case OrderSide.sell =>
        OrderBookKey(
          order.price,
          Duration.between(order.time.instant, Instant.MIN),
          order.degeneracy
        )
    }
  }
}

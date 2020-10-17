package co.firstorderlabs.fakebase

import java.time.Duration

import co.firstorderlabs.common.utils.Utils.When
import co.firstorderlabs.fakebase.currency.Configs.ProductPrice
import co.firstorderlabs.fakebase.currency.Configs.ProductPrice.ProductVolume
import co.firstorderlabs.fakebase.types.Events.LimitOrderEvent
import co.firstorderlabs.fakebase.types.Exceptions.OrderBookEmpty
import co.firstorderlabs.fakebase.types.Types.OrderId
import co.firstorderlabs.fakebase.utils.IndexedLinkedList

import scala.annotation.tailrec
import scala.collection.mutable
import scala.collection.mutable.{HashMap, TreeMap}
import scala.math.Ordering

class AggregatedMap extends HashMap[ProductPrice, ProductVolume] {
  override def apply(key: ProductPrice): ProductVolume =
    super.getOrElseUpdate(key, ProductVolume.zeroVolume)
}

case class OrderBookSnapshot(
    orderIdLookup: HashMap[OrderId, LimitOrderEvent],
    priceTree: TreeMap[ProductPrice, PriceGlob],
    priceTreeIndex: HashMap[ProductPrice, PriceGlob]
) extends Snapshot
case class OrderBookKey(price: ProductPrice, time: Duration, degeneracy: Int)

case class PriceGlob(price: ProductPrice) {
  val orders = new IndexedLinkedList[OrderBookKey, LimitOrderEvent]

  def aggregateVolume: ProductVolume = {
    var aggregatedVolume = ProductVolume.zeroVolume
    for ((_, order) <- orders.iterator) aggregatedVolume += order.size

    aggregatedVolume
  }

  override def clone(): PriceGlob = {
    val clonedPriceGlob = PriceGlob(this.price)
    clonedPriceGlob.orders.addAll(this.orders.iterator)
    clonedPriceGlob
  }

  def equalTo(that: PriceGlob): Boolean = orders equalTo that.orders

  def get(key: OrderBookKey): Option[LimitOrderEvent] = orders.get(key)

  def isEmpty: Boolean = orders.isEmpty

  def iterator: Iterator[(OrderBookKey, LimitOrderEvent)] = orders.iterator

  def oldestOrder: Option[LimitOrderEvent] = orders.head.map(_.getValue)

  def put(key: OrderBookKey, limitOrderEvent: LimitOrderEvent): Unit =
    orders.addOne(key, limitOrderEvent)

  def remove(key: OrderBookKey): Option[LimitOrderEvent] =
    orders.remove(key).flatMap(n => Some(n.getValue))
}

class OrderBook(snapshot: Option[OrderBookSnapshot] = None)
    extends Snapshotable[OrderBookSnapshot] {
  private val orderIdLookup = new HashMap[OrderId, LimitOrderEvent]
  val priceTree =
    new TreeMap[ProductPrice, PriceGlob]()(OrderBook.PriceOrdering)
  private val priceTreeIndex = new mutable.HashMap[ProductPrice, PriceGlob]

  if (snapshot.isDefined) {
    restore(snapshot.get)
  }

  override def createSnapshot: OrderBookSnapshot = {
    OrderBookSnapshot(
      orderIdLookup.clone,
      priceTree.clone,
      priceTreeIndex.clone
    )
  }

  override def restore(snapshot: OrderBookSnapshot): Unit = {
    clear
    orderIdLookup.addAll(snapshot.orderIdLookup.iterator)
    priceTree.addAll(snapshot.priceTree.iterator)
    priceTreeIndex.addAll(snapshot.priceTreeIndex.iterator)
  }

  override def clear: Unit = {
    orderIdLookup.clear
    priceTree.clear
    priceTreeIndex.clear
  }

  override def isCleared: Boolean = {
    orderIdLookup.isEmpty && priceTree.isEmpty && priceTreeIndex.isEmpty
  }

  def aggregateToMap(
      depth: Int,
      fromTop: Boolean = false
  ): Map[ProductPrice, ProductVolume] = {
    val priceTreeIterator =
      priceTree.whenElse(fromTop)(_.toList.reverseIterator, _.iterator)
    val aggregatedMap = new AggregatedMap

    @tailrec
    def populateAggregatedMap: Unit = {
      if (!priceTreeIterator.hasNext) return

      val (_, priceGlob) = priceTreeIterator.next()
      if (
        aggregatedMap.size >= depth && !aggregatedMap.contains(priceGlob.price)
      ) {} else {
        aggregatedMap(priceGlob.price) = priceGlob.aggregateVolume
        populateAggregatedMap
      }
    }

    populateAggregatedMap
    aggregatedMap.toMap
  }

  def getOrderByOrderBookKey(
      orderBookKey: OrderBookKey
  ): Option[LimitOrderEvent] = {
    val priceGlob = priceTree.get(orderBookKey.price)
    priceGlob.flatMap(_.get(orderBookKey))
  }

  def getOrderByOrderId(orderId: OrderId): Option[LimitOrderEvent] =
    orderIdLookup.get(orderId)

  def isEmpty: Boolean = {
    orderIdLookup.isEmpty
  }

  def iterator: Iterator[(OrderBookKey, LimitOrderEvent)] =
    for {
      (_, priceGlob) <- priceTree.iterator
      item <- priceGlob.iterator
    } yield item

  def maxOrder: Option[LimitOrderEvent] =
    for {
      (_, priceGlob: PriceGlob) <- priceTree.lastOption
      order <- priceGlob.oldestOrder
    } yield order

  def maxPrice: Option[ProductPrice] =
    priceTree.lastOption.map(_._1)

  def minOrder: Option[LimitOrderEvent] =
    for {
      (_, priceGlob: PriceGlob) <- priceTree.headOption
      order <- priceGlob.oldestOrder
    } yield order

  def minPrice: Option[ProductPrice] = {
    priceTree.headOption.map(_._1)
  }

  def removeByKey(key: OrderBookKey): Option[LimitOrderEvent] = {
    val price = key.price
    val priceGlob = priceTreeIndex.get(price).get
    val order = priceGlob.remove(key)

    if (priceGlob.isEmpty) {
      priceTree.remove(price)
      priceTreeIndex.remove(price)
    }
    orderIdLookup.remove(order.get.orderId)
  }

  def removeByOrderId(orderId: OrderId): Option[LimitOrderEvent] = {
    val order = orderIdLookup.get(orderId)
    val key = OrderBook.getOrderBookKey(order.get)
    removeByKey(key)
  }

  @throws[OrderBookEmpty]
  def throwExceptionIfEmpty: Unit = {
    if (isEmpty) {
      throw new OrderBookEmpty
    }
  }

  def update(key: OrderBookKey, order: LimitOrderEvent): Unit = {
    require(
      order.orderStatus.isopen,
      "can only add open orders to the order book"
    )
    if (orderIdLookup.contains(order.orderId)) { return }

    val price = order.price
    priceTreeIndex.get(price) match {
      case Some(priceGlob) => priceGlob.put(key, order)
      case None => {
        val priceGlob = PriceGlob(price)
        priceGlob.put(key, order)
        priceTreeIndex.put(price, priceGlob)
        priceTree.put(price, priceGlob)
      }
    }

    orderIdLookup.update(order.orderId, order)
  }
}

object OrderBook {
  implicit val PriceOrdering = new Ordering[ProductPrice] {
    override def compare(a: ProductPrice, b: ProductPrice): Int = {
      a.amount compareTo b.amount
    }
  }

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

  def getOrderBookKey(order: LimitOrderEvent): OrderBookKey =
    order.getOrderBookKey
}

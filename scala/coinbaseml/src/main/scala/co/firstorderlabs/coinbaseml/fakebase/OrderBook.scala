package co.firstorderlabs.coinbaseml.fakebase

import co.firstorderlabs.coinbaseml.common.utils.Utils.When
import co.firstorderlabs.coinbaseml.fakebase.types.Exceptions.OrderBookEmpty
import co.firstorderlabs.coinbaseml.fakebase.utils.IndexedLinkedList
import co.firstorderlabs.common.currency.Configs.ProductPrice
import co.firstorderlabs.common.currency.Configs.ProductPrice.ProductVolume
import co.firstorderlabs.common.types.Events.{LimitOrderEvent, OrderBookKey}
import co.firstorderlabs.common.types.Types.OrderId

import scala.annotation.tailrec
import scala.collection.mutable
import scala.collection.mutable.{HashMap, TreeMap}
import scala.math.Ordering

class AggregatedMap extends HashMap[ProductPrice, ProductVolume] {
  override def apply(key: ProductPrice): ProductVolume =
    super.getOrElseUpdate(key, ProductVolume.zeroVolume)
}

case class OrderBookSnapshot(
    orderIdLookup: mutable.HashMap[OrderId, LimitOrderEvent],
    priceTree: PriceTree
) extends Snapshot

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

  def oldestOrder: Option[LimitOrderEvent] = orders.head.map(_.value)

  def put(key: OrderBookKey, limitOrderEvent: LimitOrderEvent): Unit =
    orders.addOne(key, limitOrderEvent)

  def remove(key: OrderBookKey): Option[LimitOrderEvent] =
    orders.remove(key).flatMap(n => Some(n.value))
}

case class PriceTree(
    priceTree: TreeMap[ProductPrice, PriceGlob] = new TreeMap,
    priceTreeIndex: mutable.HashMap[ProductPrice, PriceGlob] =
      new mutable.HashMap
) {
//  private val priceTreeIndex = new mutable.HashMap[ProductPrice, PriceGlob]
//  private val priceTree = new TreeMap[ProductPrice, PriceGlob]

  def addAll(iterator: Iterator[(ProductPrice, PriceGlob)]): this.type = {
    priceTree.addAll(iterator)
    priceTreeIndex.addAll(priceTree.iterator)
    this
  }

  def clear: Unit = {
    priceTree.clear
    priceTreeIndex.clear
  }

  override def clone: PriceTree = {
    val clonedPriceTree = new PriceTree
    clonedPriceTree.addAll(iterator)
  }

  def isEmpty: Boolean = priceTree.isEmpty && priceTreeIndex.isEmpty

  def iterator: Iterator[(ProductPrice, PriceGlob)] =
    for (item <- priceTree.iterator) yield {
      (item._1, item._2.clone)
    }

  def get(key: ProductPrice): Option[PriceGlob] = priceTreeIndex.get(key)

  def headOption: Option[(ProductPrice, PriceGlob)] = priceTree.headOption

  def toList: List[(ProductPrice, PriceGlob)] = priceTree.toList

  def lastOption: Option[(ProductPrice, PriceGlob)] = priceTree.lastOption

  def put(key: ProductPrice, value: PriceGlob): Option[PriceGlob] = {
    priceTreeIndex.put(key, value)
    priceTree.put(key, value)
  }

  def remove(key: ProductPrice): Option[PriceGlob] = {
    priceTreeIndex.remove(key)
    priceTree.remove(key)
  }
}

class OrderBook(snapshot: Option[OrderBookSnapshot] = None)
    extends Snapshotable[OrderBookSnapshot] {
  private val orderIdLookup = new HashMap[OrderId, LimitOrderEvent]
  private val priceTree = new PriceTree
  snapshot.map(restore(_))

  override def createSnapshot: OrderBookSnapshot = {
    OrderBookSnapshot(
      orderIdLookup.clone,
      priceTree.clone
    )
  }

  override def restore(snapshot: OrderBookSnapshot): Unit = {
    clear
    orderIdLookup.addAll(snapshot.orderIdLookup.iterator)
    priceTree.addAll(snapshot.priceTree.iterator)
  }

  override def clear: Unit = {
    orderIdLookup.clear
    priceTree.clear
  }

  override def isCleared: Boolean = {
    orderIdLookup.isEmpty && priceTree.isEmpty
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
    val priceGlob = priceTree.get(price).get
    val order = priceGlob.remove(key).get

    if (priceGlob.isEmpty) {
      priceTree.remove(price)
//      priceTreeIndex.remove(price)
    }
    orderIdLookup.remove(order.orderId)
  }

  def removeByOrderId(orderId: OrderId): Option[LimitOrderEvent] = {
    val order = orderIdLookup.get(orderId).get
    removeByKey(order.orderBookKey)
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
    priceTree.get(price) match {
      case Some(priceGlob) => priceGlob.put(key, order)
      case None => {
        val priceGlob = PriceGlob(price)
        priceGlob.put(key, order)
        priceTree.put(price, priceGlob)
      }
    }

    orderIdLookup.update(order.orderId, order)
  }
}

object OrderBook {
  implicit class PriceIndexUtils(
      priceIndex: mutable.HashMap[ProductPrice, PriceGlob]
  ) {
    def customClone: mutable.HashMap[ProductPrice, PriceGlob] = {
      val clonedPriceIndex = new mutable.HashMap[ProductPrice, PriceGlob]
      clonedPriceIndex.addAll(priceIndex.iterator.map(i => (i._1, i._2.clone)))
    }
  }

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
    order.orderBookKey
}

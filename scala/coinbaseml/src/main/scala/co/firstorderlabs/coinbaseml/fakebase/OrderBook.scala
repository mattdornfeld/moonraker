package co.firstorderlabs.coinbaseml.fakebase

import co.firstorderlabs.coinbaseml.common.utils.Utils.When
import co.firstorderlabs.coinbaseml.fakebase.Types.Exceptions.OrderBookEmpty
import co.firstorderlabs.coinbaseml.fakebase.utils.IndexedLinkedList
import co.firstorderlabs.common.currency.Configs.ProductPrice
import co.firstorderlabs.common.currency.Configs.ProductPrice.ProductVolume
import co.firstorderlabs.common.protos.events.OrderSide
import co.firstorderlabs.common.types.Events.{LimitOrderEvent, OrderBookKey}
import co.firstorderlabs.common.types.Types.OrderId

import scala.annotation.tailrec
import scala.collection.mutable
import scala.collection.mutable.{HashMap, TreeMap}
import scala.math.Ordering

final class AggregatedMap extends HashMap[ProductPrice, ProductVolume] {
  override def apply(key: ProductPrice): ProductVolume =
    super.getOrElseUpdate(key, ProductVolume.zeroVolume)
}

final case class PriceGlob(price: ProductPrice) {
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

final case class PriceTree(
    priceTree: TreeMap[ProductPrice, PriceGlob] = new TreeMap,
    priceTreeIndex: mutable.HashMap[ProductPrice, PriceGlob] =
      new mutable.HashMap
) {
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

sealed abstract case class OrderBookState (
    orderIdLookup: mutable.HashMap[OrderId, LimitOrderEvent],
    priceTree: PriceTree,
    side: OrderSide
) {
  def clear: Unit = {
    orderIdLookup.clear
    priceTree.clear
  }

  def isCleared: Boolean = {
    orderIdLookup.isEmpty && priceTree.isEmpty
  }
}

final class BuyOrderBookState(
    orderIdLookup: mutable.HashMap[OrderId, LimitOrderEvent],
    priceTree: PriceTree
) extends OrderBookState(orderIdLookup, priceTree, OrderSide.buy) with State[BuyOrderBookState] {
  override val companion = BuyOrderBookState

  override def createSnapshot(implicit simulationState: SimulationState): BuyOrderBookState =
    new BuyOrderBookState(
      orderIdLookup.clone,
      priceTree.clone,
    )
}

object BuyOrderBookState extends StateCompanion[BuyOrderBookState] {
  override def create(implicit simulationMetadata: SimulationMetadata): BuyOrderBookState =
    new BuyOrderBookState(
      new mutable.HashMap,
      new PriceTree
    )

  override def fromSnapshot(snapshot: BuyOrderBookState): BuyOrderBookState = {
    val buyOrderBookState = new BuyOrderBookState(
      new mutable.HashMap,
      new PriceTree
    )

    buyOrderBookState.orderIdLookup.addAll(snapshot.orderIdLookup.iterator)
    buyOrderBookState.priceTree.addAll(snapshot.priceTree.iterator)
    buyOrderBookState
  }
}

final class SellOrderBookState(
    orderIdLookup: mutable.HashMap[OrderId, LimitOrderEvent],
    priceTree: PriceTree
) extends OrderBookState(orderIdLookup, priceTree, OrderSide.sell) with State[SellOrderBookState] {
  override val companion = SellOrderBookState

  override def createSnapshot(implicit simulationState: SimulationState): SellOrderBookState =
    new SellOrderBookState(
      orderIdLookup.clone,
      priceTree.clone,
    )
}

object SellOrderBookState extends StateCompanion[SellOrderBookState] {
  override def create(implicit simulationMetadata: SimulationMetadata): SellOrderBookState =
    new SellOrderBookState(
      new mutable.HashMap,
      new PriceTree
    )

  override def fromSnapshot(snapshot: SellOrderBookState): SellOrderBookState = {
    val sellOrderBookState = new SellOrderBookState(
      new mutable.HashMap,
      new PriceTree
    )

    sellOrderBookState.orderIdLookup.addAll(snapshot.orderIdLookup.iterator)
    sellOrderBookState.priceTree.addAll(snapshot.priceTree.iterator)
    sellOrderBookState
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

  def aggregateToMap(
      depth: Int,
      fromTop: Boolean = false
  )(implicit
      orderBookState: OrderBookState
  ): Map[ProductPrice, ProductVolume] = {
    val priceTreeIterator =
      orderBookState.priceTree
        .whenElse(fromTop)(_.toList.reverseIterator, _.iterator)
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

  def getOrderBookKey(order: LimitOrderEvent): OrderBookKey =
    order.orderBookKey

  def getOrderByOrderBookKey(
      orderBookKey: OrderBookKey
  )(implicit orderBookState: OrderBookState): Option[LimitOrderEvent] = {
    val priceGlob = orderBookState.priceTree.get(orderBookKey.price)
    priceGlob.flatMap(_.get(orderBookKey))
  }

  def getOrderByOrderId(
      orderId: OrderId
  )(implicit orderBookState: OrderBookState): Option[LimitOrderEvent] =
    orderBookState.orderIdLookup.get(orderId)

  def isEmpty(implicit orderBookState: OrderBookState): Boolean = {
    orderBookState.orderIdLookup.isEmpty
  }

  def iterator(implicit
      orderBookState: OrderBookState
  ): Iterator[(OrderBookKey, LimitOrderEvent)] =
    for {
      (_, priceGlob) <- orderBookState.priceTree.iterator
      item <- priceGlob.iterator
    } yield item

  def bestOrder(implicit orderBookState: OrderBookState): Option[LimitOrderEvent] =
    orderBookState match {
      case buyOrderBookState: BuyOrderBookState => maxOrder(buyOrderBookState)
      case sellOrderBookState: SellOrderBookState => minOrder(sellOrderBookState)
    }

  def bestPrice(implicit orderBookState: OrderBookState): Option[ProductPrice] =
    orderBookState match {
      case buyOrderBookState: BuyOrderBookState => maxPrice(buyOrderBookState)
      case sellOrderBookState: SellOrderBookState => minPrice(sellOrderBookState)
    }

  def maxOrder(implicit
      orderBookState: BuyOrderBookState
  ): Option[LimitOrderEvent] =
    for {
      (_, priceGlob: PriceGlob) <- orderBookState.priceTree.lastOption
      order <- priceGlob.oldestOrder
    } yield order

  def maxPrice(implicit
      orderBookState: BuyOrderBookState
  ): Option[ProductPrice] =
    orderBookState.priceTree.lastOption.map(_._1)

  def minOrder(implicit
      orderBookState: SellOrderBookState
  ): Option[LimitOrderEvent] =
    for {
      (_, priceGlob: PriceGlob) <- orderBookState.priceTree.headOption
      order <- priceGlob.oldestOrder
    } yield order

  def minPrice(implicit
      orderBookState: SellOrderBookState
  ): Option[ProductPrice] = {
    orderBookState.priceTree.headOption.map(_._1)
  }

  def removeByKey(
      key: OrderBookKey
  )(implicit orderBookState: OrderBookState): Option[LimitOrderEvent] = {
    val price = key.price
    val priceGlob = orderBookState.priceTree.get(price).get
    val order = priceGlob.remove(key).get

    if (priceGlob.isEmpty) {
      orderBookState.priceTree.remove(price)
    }
    orderBookState.orderIdLookup.remove(order.orderId)
  }

  def removeByOrderId(
      orderId: OrderId
  )(implicit orderBookState: OrderBookState): Option[LimitOrderEvent] = {
    val order = orderBookState.orderIdLookup.get(orderId).get
    removeByKey(order.orderBookKey)
  }

  @throws[OrderBookEmpty]
  def throwExceptionIfEmpty(implicit orderBookState: OrderBookState): Unit = {
    if (isEmpty) {
      throw new OrderBookEmpty
    }
  }

  def update(key: OrderBookKey, order: LimitOrderEvent)(implicit
      orderBookState: OrderBookState
  ): Unit = {
    require(
      order.orderStatus.isopen,
      "can only add open orders to the order book"
    )
    if (orderBookState.orderIdLookup.contains(order.orderId)) { return }

    val price = order.price
    orderBookState.priceTree.get(price) match {
      case Some(priceGlob) => priceGlob.put(key, order)
      case None => {
        val priceGlob = PriceGlob(price)
        priceGlob.put(key, order)
        orderBookState.priceTree.put(price, priceGlob)
      }
    }

    orderBookState.orderIdLookup.update(order.orderId, order)
  }

  implicit class PriceIndexUtils(
      priceIndex: mutable.HashMap[ProductPrice, PriceGlob]
  ) {
    def customClone: mutable.HashMap[ProductPrice, PriceGlob] = {
      val clonedPriceIndex = new mutable.HashMap[ProductPrice, PriceGlob]
      clonedPriceIndex.addAll(priceIndex.iterator.map(i => (i._1, i._2.clone)))
    }
  }
}

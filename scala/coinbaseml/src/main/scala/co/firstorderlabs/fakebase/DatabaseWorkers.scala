package co.firstorderlabs.fakebase

import java.math.BigDecimal
import java.time.{Duration, Instant}
import java.util.concurrent.{LinkedBlockingQueue => LinkedBlockingQueueBase}
import java.util.logging.Logger

import cats.effect.IO
import co.firstorderlabs.fakebase.currency.Configs.ProductPrice
import co.firstorderlabs.fakebase.currency.Configs.ProductPrice.{ProductVolume, QuoteVolume, productId}
import co.firstorderlabs.fakebase.protos.fakebase._
import co.firstorderlabs.fakebase.types.Events.Event
import co.firstorderlabs.fakebase.types.Types._
import doobie.implicits._
import doobie.implicits.legacy.instant._
import doobie.{Meta, Query0, Transactor}

import scala.annotation.tailrec
import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext

case class QueryResult(buyLimitOrders: List[BuyLimitOrder],
                       buyMarketOrders: List[BuyMarketOrder],
                       cancellations: List[Cancellation],
                       sellLimitOrders: List[SellLimitOrder],
                       sellMarketOrder: List[SellMarketOrder],
                       timeInterval: TimeInterval)

final case class BoundedTrieMap[K, V](maxSize: Int, trieMap: Option[TrieMap[K, V]] = None) {
  val _trieMap = if (trieMap.isEmpty) new TrieMap[K, V] else trieMap.get

  def addAll(xs: IterableOnce[(K, V)]): Unit = {
    _trieMap.addAll(xs)
  }

  def clear: Unit = _trieMap.clear

  def isEmpty: Boolean = _trieMap.isEmpty

  @tailrec
  def remove(key: K): V = {
    val value = _trieMap.remove(key)
    if (value.isDefined) {
      value.get
    } else {
      Thread.sleep(1)
      remove(key)
    }
  }

  def iterator: Iterator[(K, V)] = {
    _trieMap.iterator
  }

  @tailrec
  def put(key: K, value: V): Option[V] = {
    if (size >= maxSize) {
      Thread.sleep(10)
      put(key, value)
    } else {
      _trieMap.put(key, value)
    }
  }

  def size: Int = _trieMap.size

  override def clone: BoundedTrieMap[K, V] = {
    BoundedTrieMap(maxSize, Some(_trieMap.clone))
  }
}

class DatabaseWorkers extends Thread {
  private var paused = false

  def pauseWorker: Unit = synchronized {
    paused = true
  }

  def unpauseWorker: Unit = synchronized {
    paused = false
    notify
  }

  @tailrec
  final def isPaused: Boolean = {
    Thread.sleep(10)
    val threadState = synchronized {getState}
    if (threadState.compareTo(Thread.State.WAITING) == 0) {
      true
    } else {
      isPaused
    }
  }

  override def run(): Unit = populateQueryResultQueue

  @tailrec
  private def populateQueryResultQueue: Unit = {
    if (paused) synchronized{wait}
    if (!DatabaseWorkers.timeIntervalQueue.isEmpty) {
      val timeInterval = DatabaseWorkers.timeIntervalQueue.take
      DatabaseWorkers.populateQueryResultMap(timeInterval)
      DatabaseWorkers.logger.fine(s"retrieved data for timeInterval ${timeInterval}")
    } else {
      Thread.sleep(10)
    }
    populateQueryResultQueue
  }
}

class LinkedBlockingQueue[A] extends LinkedBlockingQueueBase[A] {
  override def equals(that: Any): Boolean = {
    that match {
      case that: LinkedBlockingQueue[A] => this.toArray.toList == that.toArray.toList
      case _ => false
    }
  }
}

case class DatabaseWorkersCheckpoint(timeIntervalQueue: LinkedBlockingQueue[TimeInterval]) extends Checkpoint

object DatabaseWorkers extends Checkpointable[DatabaseWorkersCheckpoint] {
  // Specify how to convert from JDBC supported data types to custom data types
  implicit val dateTimeConverter: Meta[Datetime] = Meta[Instant].timap(value => Datetime(value))(value => value.instant)
  implicit val doneReasonConverter: Meta[DoneReason] = Meta[Int].timap(value => DoneReason.fromValue(value))(value => value.value)
  implicit val matchEvents: Meta[MatchEvents] = Meta[String].timap(_ => MatchEvents())(_ => "")
  implicit val orderIdConverter: Meta[OrderId] = Meta[String].timap(value => OrderId(value))(value => value.orderId)
  implicit val orderRequestIdConverter: Meta[OrderRequestId] = Meta[String].timap(value => OrderRequestId(value))(value => value.orderRequestId)
  implicit val orderSideConverter: Meta[OrderSide] = Meta[String].timap(value => OrderSide.fromName(value).get)(value => value.name)
  implicit val orderStatusConverter: Meta[OrderStatus] = Meta[String].timap(value => OrderStatus.fromName(value).get)(value => value.name)
  implicit val productIdConverter: Meta[ProductId] = Meta[String].timap(value => ProductId.fromString(value))(value => value.toString)
  implicit val productPriceConverter: Meta[ProductPrice] = Meta[BigDecimal].timap(value => new ProductPrice(Left(value)))(value => value.amount)
  implicit val productVolumeConverter: Meta[ProductVolume] = Meta[BigDecimal].timap(value => new ProductVolume(Left(value)))(value => value.amount)
  implicit val quoteVolumeConverter: Meta[QuoteVolume] = Meta[BigDecimal].timap(value => new QuoteVolume(Left(value)))(value => value.amount)
  implicit val rejectReasonConverter: Meta[RejectReason] = Meta[Int].timap(value => RejectReason.fromValue(value))(value => value.value)

  implicit val buyLimitOrderRequestConverter: Meta[BuyLimitOrderRequest] = Meta[String].timap(_ => new BuyLimitOrderRequest)(_ => "")
  implicit val buyMarketOrderRequestConverter: Meta[BuyMarketOrderRequest] = Meta[String].timap(_ => new BuyMarketOrderRequest())(_ => "")

  implicit val contextShift = IO.contextShift(ExecutionContext.global)
  private val logger = Logger.getLogger(DatabaseWorkers.toString)
  private val queryResultMap = new BoundedTrieMap[TimeInterval, QueryResult](Configs.maxResultsQueueSize)
  private val transactor = Transactor.fromDriverManager[IO](
    "org.postgresql.Driver",
    "jdbc:postgresql://" + Configs.postgresDbHost + "/" + Configs.postgresTable,
    Configs.postgresUsername,
    Configs.postgresPassword,
  )
  private val timeIntervalQueue = new LinkedBlockingQueue[TimeInterval]
  private val workers = for(_ <- (1 to Configs.numDatabaseWorkers)) yield new DatabaseWorkers
  workers.foreach(w => w.start)

  override def checkpoint: DatabaseWorkersCheckpoint = {
    val checkpointTimeIntervalQueue = new LinkedBlockingQueue[TimeInterval]
    populateTimeIntervalQueue(
      Exchange.simulationMetadata.get.checkpointTimeInterval.startTime,
      Exchange.simulationMetadata.get.endTime,
      Exchange.simulationMetadata.get.timeDelta,
      checkpointTimeIntervalQueue)
    DatabaseWorkersCheckpoint(checkpointTimeIntervalQueue)
  }

  override def clear: Unit = {
    pauseWorkers
    queryResultMap.clear
    timeIntervalQueue.clear
  }

  override def isCleared: Boolean = {
    (queryResultMap.isEmpty
     && timeIntervalQueue.isEmpty)
  }

  override def restore(checkpoint: DatabaseWorkersCheckpoint): Unit = {
    clear
    timeIntervalQueue.addAll(checkpoint.timeIntervalQueue)
    unpauseWorkers
  }

  def getQueryResult(timeInterval: TimeInterval): QueryResult = {
    queryResultMap.remove(timeInterval)
  }

  def getResultMapSize: Int = queryResultMap.size

  def isPaused: Boolean = {
    workers.forall(w => w.isPaused)
  }

  def start(startTime: Datetime,
            endTime: Datetime,
            timeDelta: Duration): Unit = {
    pauseWorkers
    clear
    populateTimeIntervalQueue(startTime, endTime, timeDelta, timeIntervalQueue)
    unpauseWorkers
    logger.info(s"DatabaseWorkers started for ${startTime}-${endTime} with timeDelta ${timeDelta}")
  }

  private def executeQuery[A <: Event](query: Query0[A]): List[A] = {
    if (Configs.isTest) {
      List[A]()
    } else {
      query
        .stream
        .compile
        .toList
        .transact(transactor)
        .unsafeRunSync
    }
  }

  private def pauseWorkers: Unit = {
    workers.foreach(w => w.pauseWorker)
    isPaused
  }

  private def unpauseWorkers: Unit = {
    workers.foreach(w => w.unpauseWorker)
  }

  private def populateQueryResultMap(timeInterval: TimeInterval): Unit = {
    //TODO: Try adding below queries to single transaction
    val queryResult = QueryResult(
      executeQuery(queryBuyLimitOrders(productId, timeInterval)),
      executeQuery(queryBuyMarketOrders(productId, timeInterval)),
      executeQuery(queryCancellations(productId, timeInterval)),
      executeQuery(querySellLimitOrders(productId, timeInterval)),
      executeQuery(querySellMarketOrders(productId, timeInterval)),
      timeInterval
    )

    logger.fine(s"Retrieved query results for time interval ${timeInterval}")

    queryResultMap.put(timeInterval, queryResult)
  }

  @tailrec
  private def populateTimeIntervalQueue(startTime: Datetime,
                                        endTime: Datetime,
                                        timeDelta: Duration,
                                        timeIntervalQueue: LinkedBlockingQueue[TimeInterval]
                                       ): Unit = {
    if (endTime.instant.isAfter(startTime.instant)) {
      val intervalEndTime = Datetime(startTime.instant.plus(timeDelta))
      timeIntervalQueue.put(TimeInterval(startTime, intervalEndTime))
      populateTimeIntervalQueue(intervalEndTime, endTime, timeDelta, timeIntervalQueue)
    }
  }

  private def queryCancellations(productId: ProductId, timeInterval: TimeInterval): Query0[Cancellation] = {
    sql"""SELECT
            order_id,
            price,
            product_id,
            side,
            remaining_size,
            time
          FROM coinbase_cancellations
          WHERE time BETWEEN ${timeInterval.startTime.toString}::timestamp and ${timeInterval.endTime.toString}::timestamp
          AND product_id = ${productId.toString}
       """
      .query[Cancellation]
  }

  private def queryBuyLimitOrders(productId: ProductId, timeInterval: TimeInterval): Query0[BuyLimitOrder] = {
    sql"""SELECT
            order_id,
            order_status,
            price,
            product_id,
            side,
            size,
            time,
            ${RejectReason.notRejected.value},
            '',
            ${Instant.EPOCH.toString}::timestamp,
            ${DoneReason.notDone.value},
            ''
          FROM coinbase_orders
          WHERE time BETWEEN ${timeInterval.startTime.toString}::timestamp AND ${timeInterval.endTime.toString}::timestamp
          AND product_id = ${productId.toString}
          AND order_type = ${OrderType.limit.name}
          AND side = ${OrderSide.buy.name}
       """
      .query[BuyLimitOrder]
  }

  private def queryBuyMarketOrders(productId: ProductId, timeInterval: TimeInterval): Query0[BuyMarketOrder] = {
    sql"""SELECT
            funds,
            order_id,
            order_status,
            product_id,
            side,
            time,
            ${RejectReason.notRejected.value},
            '',
            ${Instant.EPOCH.toString}::timestamp,
            ${DoneReason.notDone.value},
            ''
          FROM coinbase_orders
          WHERE time BETWEEN ${timeInterval.startTime.toString}::timestamp AND ${timeInterval.endTime.toString}::timestamp
          AND product_id = ${productId.toString}
          AND order_type = ${OrderType.market.name}
          AND side = ${OrderSide.buy.name}
       """
      .query[BuyMarketOrder]
  }

  private def querySellLimitOrders(productId: ProductId, timeInterval: TimeInterval): Query0[SellLimitOrder] = {
    sql"""SELECT
            order_id,
            order_status,
            price,
            product_id,
            side,
            size,
            time,
            ${RejectReason.notRejected.value},
            '',
            ${Instant.EPOCH.toString}::timestamp,
            ${DoneReason.notDone.value},
            ''
          FROM coinbase_orders
          WHERE time BETWEEN ${timeInterval.startTime.toString}::timestamp and ${timeInterval.endTime.toString}::timestamp
          AND product_id = ${productId.toString}
          AND order_type = ${OrderType.limit.name}
          AND side = ${OrderSide.sell.name}
       """
      .query[SellLimitOrder]
  }

  private def querySellMarketOrders(productId: ProductId, timeInterval: TimeInterval): Query0[SellMarketOrder] = {
    sql"""SELECT
            order_id,
            order_status,
            product_id,
            side,
            size,
            time,
            ${RejectReason.notRejected.value},
            '',
            ${Instant.EPOCH.toString}::timestamp,
            ${DoneReason.notDone.value},
            ''
          FROM coinbase_orders
          WHERE time BETWEEN ${timeInterval.startTime.toString}::timestamp and ${timeInterval.endTime.toString}::timestamp
          AND product_id = ${productId.toString}
          AND order_type = ${OrderType.market.name}
          AND side = ${OrderSide.sell.name}
       """
      .query[SellMarketOrder]
  }
}

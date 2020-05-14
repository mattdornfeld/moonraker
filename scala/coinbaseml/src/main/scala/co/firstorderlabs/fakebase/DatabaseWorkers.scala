package co.firstorderlabs.fakebase

import java.math.BigDecimal
import java.time.{Duration, Instant}
import java.util.Comparator
import java.util.concurrent.LinkedBlockingQueue
import java.util.logging.Logger

import cats.effect.IO
import co.firstorderlabs.fakebase.currency.Configs.ProductPrice
import co.firstorderlabs.fakebase.currency.Configs.ProductPrice.{ProductVolume, QuoteVolume, productId}
import co.firstorderlabs.fakebase.protos.fakebase._
import co.firstorderlabs.fakebase.types.Events.Event
import co.firstorderlabs.fakebase.types.Types.{Datetime, OrderId, OrderRequestId, ProductId, TimeInterval}
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

class QueryResultComparator extends Comparator[QueryResult] {
    def compare(result1: QueryResult, result2: QueryResult): Int = {
      result1.timeInterval.startDt.instant compareTo result2.timeInterval.startDt.instant
    }
}

final case class BoundedTrieMap[K, V](maxSize: Int) {
  private val trieMap = new TrieMap[K, V]

  @tailrec
  def get(key: K): V = {
    val value = trieMap.get(key)
    if (value.isDefined) {
      value.get
    } else {
      Thread.sleep(1)
      get(key)
    }
  }

  @tailrec
  def put(key: K, value: V): Option[V] = {
    if (trieMap.size >= maxSize) {
      put(key, value)
    } else {
      Thread.sleep(1)
      trieMap.put(key, value)
    }
  }

  def size: Int = trieMap.size
}

class DatabaseWorkers extends Thread {
  override def run() = {
    DatabaseWorkers.timeIntervalQueue.parallelStream.forEach(DatabaseWorkers.populateQueryResultQueue)
  }
}

object DatabaseWorkers {
  // TODO: Convert get/put to meta to save space
  implicit val dateTimeConverter: Meta[Datetime] = Meta[Instant].timap(value => Datetime(value))(value => value.instant)
  implicit val doneReasonConverter: Meta[DoneReason] = Meta[Int].timap(value => DoneReason.fromValue(value))(value => value.value)
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
//  val queryResultQueue = new PriorityBlockingQueue[QueryResult](Configs.maxResultsQueueSize, new QueryResultComparator())
  private val logger = Logger.getLogger(DatabaseWorkers.toString)
  private val queryResultMap = new BoundedTrieMap[TimeInterval, QueryResult](Configs.maxResultsQueueSize)
  private val transactor = Transactor.fromDriverManager[IO](
    "org.postgresql.Driver",
    "jdbc:postgresql://" + Configs.postgresDbHost + "/" + Configs.postgresTable,
    Configs.postgresUsername,
    Configs.postgresPassword,
  )
  private val timeIntervalQueue = createTimeIntervalQueue(Configs.startTime, Configs.endTime, Configs.timeDelta)

  def getQueryResult(timeInterval: TimeInterval): QueryResult = {
    queryResultMap.get(timeInterval)
  }

  def getResultMapSize: Int = queryResultMap.size

  private def createTimeIntervalQueue(startTime: Datetime, endTime: Datetime, timeDelta: Duration): LinkedBlockingQueue[TimeInterval] = {
    val timeIntervalQueue = new LinkedBlockingQueue[TimeInterval]()

    var intervalStartTime = startTime
    var intervalEndTime = timeDelta.addTo(intervalStartTime.instant).asInstanceOf[Instant]
    while (endTime.instant.isAfter(intervalStartTime.instant)) {
      timeIntervalQueue.add(
        TimeInterval(intervalStartTime, Datetime(intervalEndTime))
      )

      intervalStartTime = Datetime(intervalEndTime)
      intervalEndTime = timeDelta.addTo(intervalEndTime).asInstanceOf[Instant]
    }

    timeIntervalQueue

  }

  private def populateQueryResultQueue(timeInterval: TimeInterval): Unit = {
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

  private def executeQuery[A <: Event](query: Query0[A]): List[A] = {
    query
      .stream
      .compile
      .toList
      .transact(transactor)
      .unsafeRunSync
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
          WHERE time BETWEEN ${timeInterval.startDt.toString}::timestamp and ${timeInterval.endDt.toString}::timestamp
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
            ${DoneReason.notDone.value}
          FROM coinbase_orders
          WHERE time BETWEEN ${timeInterval.startDt.toString}::timestamp AND ${timeInterval.endDt.toString}::timestamp
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
            ${DoneReason.notDone.value}
          FROM coinbase_orders
          WHERE time BETWEEN ${timeInterval.startDt.toString}::timestamp AND ${timeInterval.endDt.toString}::timestamp
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
            ${DoneReason.notDone.value}
          FROM coinbase_orders
          WHERE time BETWEEN ${timeInterval.startDt.toString}::timestamp and ${timeInterval.endDt.toString}::timestamp
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
            ${DoneReason.notDone.value}
          FROM coinbase_orders
          WHERE time BETWEEN ${timeInterval.startDt.toString}::timestamp and ${timeInterval.endDt.toString}::timestamp
          AND product_id = ${productId.toString}
          AND order_type = ${OrderType.market.name}
          AND side = ${OrderSide.sell.name}
       """
      .query[SellMarketOrder]
  }
}

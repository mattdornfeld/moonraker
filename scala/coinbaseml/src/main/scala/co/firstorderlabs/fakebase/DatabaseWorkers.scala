package co.firstorderlabs.fakebase

import java.math.BigDecimal
import java.time.{Duration, Instant}
import java.util.Comparator
import java.util.concurrent.LinkedBlockingQueue

import cats.effect.IO
import co.firstorderlabs.fakebase.currency.Configs.ProductPrice
import co.firstorderlabs.fakebase.currency.Configs.ProductPrice.{ProductVolume, QuoteVolume, productId}
import co.firstorderlabs.fakebase.protos.fakebase._
import co.firstorderlabs.fakebase.types.Events.Event
import co.firstorderlabs.fakebase.types.Types.{Datetime, OrderId, ProductId, TimeInterval}
import doobie.implicits._
import doobie.implicits.legacy.instant._
import doobie.util.{Get, Put}
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
      get(key)
    }
  }

  @tailrec
  def put(key: K, value: V): Option[V] = {
    if (trieMap.size >= maxSize) {
      put(key, value)
    } else {
      trieMap.put(key, value)
    }
  }
}

class DatabaseWorkers extends Thread{
  override def run() = {
    DatabaseWorkers.timeIntervalQueue.parallelStream.forEach(DatabaseWorkers.populateQueryResultQueue)
  }
}

object DatabaseWorkers {
  // TODO: Convert get/put to meta to save space
  implicit val dateTimeConverter: Meta[Datetime] = Meta[Instant].timap(value => Datetime(value))(value => value.instant)
  implicit val doneReasonConverter: Meta[DoneReason] = Meta[Int].timap(value => DoneReason.fromValue(value))(value => value.value)
  implicit val orderIdGet: Get[OrderId] = Get[String].tmap(value => OrderId(value))
  implicit val orderIdPut: Put[OrderId] = Put[String].tcontramap(value => value.orderId)
  implicit val orderSideGet: Get[OrderSide] = Get[String].tmap(value => OrderSide.fromName(value).get)
  implicit val orderSidePut: Put[OrderSide] = Put[String].tcontramap(value => value.name)
  implicit val orderStatusGet: Get[OrderStatus] = Get[String].tmap(value => OrderStatus.fromName(value).get)
  implicit val orderStatusPut: Put[OrderStatus] = Put[String].tcontramap(value => value.name)
  implicit val productIdGet: Get[ProductId] = Get[String].tmap(value => ProductId.fromString(value))
  implicit val productIdPut: Put[ProductId] = Put[String].tcontramap(value => value.toString)
  implicit val productPriceGet: Get[ProductPrice] = Get[BigDecimal].tmap(value => new ProductPrice(Left(value)))
  implicit val productPricePut: Put[ProductPrice] = Put[BigDecimal].tcontramap(value => value.amount)
  implicit val productVolumeGet: Get[ProductVolume] = Get[BigDecimal].tmap(value => new ProductVolume(Left(value)))
  implicit val productVolumePut: Put[ProductVolume] = Put[BigDecimal].tcontramap(value => value.amount)
  implicit val quoteVolumeGet: Get[QuoteVolume] = Get[BigDecimal].tmap(value => new QuoteVolume(Left(value)))
  implicit val quoteVolumePut: Put[QuoteVolume] = Put[BigDecimal].tcontramap(value => value.amount)
  implicit val rejectReasonConverter: Meta[RejectReason] = Meta[Int].timap(value => RejectReason.fromValue(value))(value => value.value)

  implicit val contextShift = IO.contextShift(ExecutionContext.global)
//  val queryResultQueue = new PriorityBlockingQueue[QueryResult](Configs.maxResultsQueueSize, new QueryResultComparator())
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
    sql"""SELECT order_id, price, product_id, side, remaining_size, time
          FROM coinbase_cancellations
          WHERE time BETWEEN ${timeInterval.startDt.toString}::timestamp and ${timeInterval.endDt.toString}::timestamp
          AND product_id = ${productId.toString}
       """
      .query[Cancellation]
  }

  private def queryBuyLimitOrders(productId: ProductId, timeInterval: TimeInterval): Query0[BuyLimitOrder] = {
    sql"""SELECT order_id, order_status, price, product_id, side, size, time
          FROM coinbase_orders
          WHERE time BETWEEN ${timeInterval.startDt.toString}::timestamp and ${timeInterval.endDt.toString}::timestamp
          AND product_id = ${productId.toString}
          AND order_type = ${OrderType.limit.name}
          AND side = ${OrderSide.buy.name}
       """
      .query[BuyLimitOrder]
  }

  private def queryBuyMarketOrders(productId: ProductId, timeInterval: TimeInterval): Query0[BuyMarketOrder] = {
    sql"""SELECT funds, order_id, order_status, product_id, side, time
          FROM coinbase_orders
          WHERE time BETWEEN ${timeInterval.startDt.toString}::timestamp and ${timeInterval.endDt.toString}::timestamp
          AND product_id = ${productId.toString}
          AND order_type = ${OrderType.market.name}
          AND side = ${OrderSide.buy.name}
       """
      .query[BuyMarketOrder]
  }

  private def querySellLimitOrders(productId: ProductId, timeInterval: TimeInterval): Query0[SellLimitOrder] = {
    sql"""SELECT order_id, order_status, price, product_id, side, size, time
          FROM coinbase_orders
          WHERE time BETWEEN ${timeInterval.startDt.toString}::timestamp and ${timeInterval.endDt.toString}::timestamp
          AND product_id = ${productId.toString}
          AND order_type = ${OrderType.limit.name}
          AND side = ${OrderSide.sell.name}
       """
      .query[SellLimitOrder]
  }

  private def querySellMarketOrders(productId: ProductId, timeInterval: TimeInterval): Query0[SellMarketOrder] = {
    sql"""SELECT order_id, order_status, product_id, side, size, time
          FROM coinbase_orders
          WHERE time BETWEEN ${timeInterval.startDt.toString}::timestamp and ${timeInterval.endDt.toString}::timestamp
          AND product_id = ${productId.toString}
          AND order_type = ${OrderType.market.name}
          AND side = ${OrderSide.sell.name}
       """
      .query[SellMarketOrder]
  }
}

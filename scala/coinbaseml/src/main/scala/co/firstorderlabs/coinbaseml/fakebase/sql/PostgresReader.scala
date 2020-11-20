package co.firstorderlabs.coinbaseml.fakebase.sql

import java.time.Instant

import co.firstorderlabs.coinbaseml.fakebase.sql.Implicits._
import co.firstorderlabs.coinbaseml.fakebase.sql.{Configs => SqlConfigs}
import co.firstorderlabs.common.protos.events.{BuyLimitOrder, BuyMarketOrder, Cancellation, DoneReason, OrderSide, RejectReason, SellLimitOrder, SellMarketOrder}
import co.firstorderlabs.common.protos.fakebase.OrderType
import co.firstorderlabs.common.types.Types.{ProductId, TimeInterval}
import doobie.implicits._
import doobie.util.query.Query0
import doobie.util.transactor.Strategy

final class PostgresReader extends DatabaseReaderThread(
  PostgresReader.buildQueryResult,
  PostgresReader.queryResultMap,
  PostgresReader.timeIntervalQueue
)

object PostgresReader
    extends DatabaseReaderBase(
      "org.postgresql.Driver",
      "jdbc:postgresql://" + SqlConfigs.postgresDbHost + "/" + SqlConfigs.postgresTable,
      SqlConfigs.postgresUsername,
      SqlConfigs.postgresPassword,
      Strategy.default
    ) {
  protected val queryResultMap = new BoundedTrieMap[TimeInterval, QueryResult](
    SqlConfigs.maxResultsQueueSize
  )
  protected val timeIntervalQueue = new LinkedBlockingQueue[TimeInterval]
  protected val workers: Seq[PostgresReader] =
    for (_ <- (1 to SqlConfigs.numDatabaseReaderThreads))
      yield new PostgresReader
  workers.foreach(w => w.start)

  protected def queryCancellations(
      productId: ProductId,
      timeInterval: TimeInterval
  ): Query0[Cancellation] = {
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

  protected def queryBuyLimitOrders(
      productId: ProductId,
      timeInterval: TimeInterval
  ): Query0[BuyLimitOrder] = {
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
            '',
            0
          FROM coinbase_orders
          WHERE time BETWEEN ${timeInterval.startTime.toString}::timestamp AND ${timeInterval.endTime.toString}::timestamp
          AND product_id = ${productId.toString}
          AND order_type = ${OrderType.limit.name}
          AND side = ${OrderSide.buy.name}
       """
      .query[BuyLimitOrder]
  }

  protected def queryBuyMarketOrders(
      productId: ProductId,
      timeInterval: TimeInterval
  ): Query0[BuyMarketOrder] = {
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

  protected def querySellLimitOrders(
      productId: ProductId,
      timeInterval: TimeInterval
  ): Query0[SellLimitOrder] = {
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
            '',
            0
          FROM coinbase_orders
          WHERE time BETWEEN ${timeInterval.startTime.toString}::timestamp and ${timeInterval.endTime.toString}::timestamp
          AND product_id = ${productId.toString}
          AND order_type = ${OrderType.limit.name}
          AND side = ${OrderSide.sell.name}
       """
      .query[SellLimitOrder]
  }

  protected def querySellMarketOrders(
      productId: ProductId,
      timeInterval: TimeInterval
  ): Query0[SellMarketOrder] = {
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

  def queryResultMapMaxSize: Int = queryResultMap.maxSize

  def setQueryResultMapMaxSize(maxSize: Int): Unit =
    queryResultMap.maxSize = maxSize

  def timeIntervalQueueElements: List[TimeInterval] =
    timeIntervalQueue.toArray.toList.asInstanceOf[List[TimeInterval]]

  def timeIntervalQueueSize: Int = timeIntervalQueue.size
}

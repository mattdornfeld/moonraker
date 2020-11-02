package co.firstorderlabs.coinbaseml.fakebase.sql

import java.time.{Duration, Instant}

import co.firstorderlabs.coinbaseml.fakebase.sql.Implicits._
import co.firstorderlabs.coinbaseml.fakebase.types.Types._
import co.firstorderlabs.common.protos.fakebase.{BuyLimitOrder, BuyMarketOrder, Cancellation, DoneReason, OrderSide, OrderType, RejectReason, SellLimitOrder, SellMarketOrder}
import doobie.Query0
import doobie.implicits._
import doobie.util.transactor.Strategy

import scala.collection.mutable

object BigQueryReader
    extends DatabaseReaderBase(
      "com.simba.googlebigquery.jdbc42.Driver",
      "jdbc:bigquery://https://www.googleapis.com/bigquery/v2:443;" +
        s"ProjectId=${Configs.gcpProjectId};" +
        "OAuthType=0;" +
        s"OAuthServiceAcctEmail=${Configs.serviceAccountEmail};" +
        s"OAuthPvtKeyPath=${Configs.serviceAccountJsonPath};" +
        s"DefaultDataset=${Configs.datasetId}",
      "",
      "",
      Strategy.void,
    ) {
  protected val queryResultMap = new mutable.HashMap[TimeInterval, QueryResult]

  def getQueryResult(timeInterval: TimeInterval): QueryResult = queryResultMap(timeInterval)

  def start(startTime: Instant, endTime: Instant, timeDelta: Duration): Unit = {
    clear
    populateQueryResultMap(TimeInterval(startTime, endTime), timeDelta)
  }

  protected def populateQueryResultMap(
      timeInterval: TimeInterval,
      timeDelta: Duration
  ): Unit = {
    //TODO: Try adding below queries to single transaction
    logger.info(s"Querying BigQuery for events in ${timeInterval}")

    buildQueryResult(timeInterval)
      .chunkByTimeDelta(timeDelta)
      .foreach(queryResult =>
        queryResultMap.put(queryResult.timeInterval, queryResult)
      )
    logger.info(
      s"Successfully wrote ${queryResultMap.size} TimeInterval keys to queryResultMap"
    )
    val numEmptyTimeIntervals =
      queryResultMap.map(_._2.buyLimitOrders.size).count(_ == 0)

    if (numEmptyTimeIntervals > 0) {
      logger.warning(
        s"There were ${numEmptyTimeIntervals} empty time intervals returned in this query"
      )
    }
  }

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
          WHERE time BETWEEN ${timeInterval.startTime.toString} and ${timeInterval.endTime.toString}
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
            TIMESTAMP(${Instant.EPOCH.toString}),
            ${DoneReason.notDone.value},
            '',
            0
          FROM coinbase_orders
          WHERE time BETWEEN ${timeInterval.startTime.toString} AND ${timeInterval.endTime.toString}
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
            TIMESTAMP(${Instant.EPOCH.toString}),
            ${DoneReason.notDone.value},
            ''
          FROM coinbase_orders
          WHERE time BETWEEN ${timeInterval.startTime.toString} AND ${timeInterval.endTime.toString}
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
            TIMESTAMP(${Instant.EPOCH.toString}),
            ${DoneReason.notDone.value},
            '',
            0
          FROM coinbase_orders
          WHERE time BETWEEN ${timeInterval.startTime.toString} and ${timeInterval.endTime.toString}
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
            TIMESTAMP(${Instant.EPOCH.toString}),
            ${DoneReason.notDone.value},
            ''
          FROM coinbase_orders
          WHERE time BETWEEN ${timeInterval.startTime.toString} and ${timeInterval.endTime.toString}
          AND product_id = ${productId.toString}
          AND order_type = ${OrderType.market.name}
          AND side = ${OrderSide.sell.name}
       """
      .query[SellMarketOrder]
  }

  override def clear: Unit = queryResultMap.clear

  override def createSnapshot: DatabaseReaderSnapshot =
    DatabaseReaderSnapshot(new LinkedBlockingQueue[TimeInterval])

  override def isCleared: Boolean = queryResultMap.isEmpty

  override def restore(snapshot: DatabaseReaderSnapshot): Unit = {}
}

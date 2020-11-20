package co.firstorderlabs.coinbaseml.fakebase.sql

import java.time.{Duration, Instant}

import co.firstorderlabs.coinbaseml.fakebase.sql.Implicits._
import co.firstorderlabs.coinbaseml.fakebase.sql.{Configs => SqlConfigs}
import co.firstorderlabs.common.protos.events.{
  BuyLimitOrder,
  BuyMarketOrder,
  Cancellation,
  DoneReason,
  OrderSide,
  RejectReason,
  SellLimitOrder,
  SellMarketOrder
}
import co.firstorderlabs.common.protos.fakebase.OrderType
import co.firstorderlabs.common.types.Types._
import doobie.Query0
import doobie.implicits._
import doobie.util.transactor.Strategy

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

final class BigQueryReader
    extends DatabaseReaderThread(
      BigQueryReader.loadQueryResultToMemory,
      BigQueryReader.queryResultMap,
      BigQueryReader.timeIntervalQueue
    )

object BigQueryReader
    extends DatabaseReaderBase(
      "com.simba.googlebigquery.jdbc42.Driver",
      "jdbc:bigquery://https://www.googleapis.com/bigquery/v2:443;" +
        "EnableHighThroughputAPI=1;" +
        s"ProjectId=${Configs.gcpProjectId};" +
        "OAuthType=0;" +
        s"OAuthServiceAcctEmail=${Configs.serviceAccountEmail};" +
        s"OAuthPvtKeyPath=${Configs.serviceAccountJsonPath};" +
        s"DefaultDataset=${Configs.datasetId};" +
        s"Timeout=${Configs.queryTimeout}",
      "",
      "",
      Strategy.void
    ) {
  protected val queryResultMap = new BoundedTrieMap[TimeInterval, QueryResult](
    SqlConfigs.maxResultsQueueSize
  )
  protected val timeIntervalQueue = new LinkedBlockingQueue[TimeInterval]
  protected val workers =
    for (_ <- (1 to SqlConfigs.numDatabaseReaderThreads))
      yield new BigQueryReader
  workers.foreach(w => w.start)

  private def loadQueryResultToMemory(
      timeInterval: TimeInterval
  ): Option[QueryResult] =
    SwayDbStorage.get(timeInterval)

  override def start(
      startTime: Instant,
      endTime: Instant,
      timeDelta: Duration
  ): Future[Unit] = {
    super.start(startTime, endTime, timeDelta)
    val readTimeIntervals =
      TimeInterval(startTime, endTime).chunkBy(Configs.bigQueryReadTimeDelta)

    Future {
      readTimeIntervals.foreach(timeInterval =>
        populateQueryResultMap(timeInterval, timeDelta)
      )
    }
  }

  protected def populateQueryResultMap(
      timeInterval: TimeInterval,
      timeDelta: Duration
  ): Unit = {
    if (SwayDbStorage.containsDataForQuery(timeInterval, timeDelta)) {
      logger.info(s"Data for (${timeInterval}, ${timeDelta}) found locally. Skipping read from BigQuery.")
    } else {
      logger.info(s"Querying BigQuery for events in ${timeInterval}")

      val queryResults =
        buildQueryResult(timeInterval).get.chunkByTimeDelta(timeDelta)

      SwayDbStorage.addAll(
        queryResults
          .map(queryResult => (queryResult.timeInterval, queryResult))
          .iterator
      )

      SwayDbStorage.recordQuerySuccess(timeInterval, timeDelta)

      logger.info(
        s"Successfully wrote ${queryResults.size} TimeInterval keys to queryResultMap"
      )
      val numEmptyTimeIntervals = queryResults.map(_.events.size).count(_ == 0)

      if (numEmptyTimeIntervals > 0) {
        logger.warning(
          s"There were ${numEmptyTimeIntervals} empty time intervals returned in this query"
        )
      }
    }
  }

  protected def queryCancellations(
      productId: ProductId,
      timeInterval: TimeInterval
  ): Query0[Cancellation] = {
    sql"""SELECT DISTINCT
            order_id,
            price,
            product_id,
            side,
            remaining_size,
            time
          FROM cancellations
          WHERE time BETWEEN ${timeInterval.startTime.toString} and ${timeInterval.endTime.toString}
          AND product_id = ${productId.toString}
       """
      .query[Cancellation]
  }

  protected def queryBuyLimitOrders(
      productId: ProductId,
      timeInterval: TimeInterval
  ): Query0[BuyLimitOrder] = {
    sql"""SELECT DISTINCT
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
          FROM orders
          WHERE time BETWEEN ${timeInterval.startTime.toString} AND ${timeInterval.endTime.toString}
          AND product_id = ${productId.toString}
          AND order_type = ${OrderType.limit.name}
          AND side = ${OrderSide.buy.name}
          AND price > 0
          AND size > 0
       """
      .query[BuyLimitOrder]
  }

  protected def queryBuyMarketOrders(
      productId: ProductId,
      timeInterval: TimeInterval
  ): Query0[BuyMarketOrder] = {
    sql"""SELECT DISTINCT
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
          FROM orders
          WHERE time BETWEEN ${timeInterval.startTime.toString} AND ${timeInterval.endTime.toString}
          AND product_id = ${productId.toString}
          AND order_type = ${OrderType.market.name}
          AND side = ${OrderSide.buy.name}
          AND funds > 0
       """
      .query[BuyMarketOrder]
  }

  protected def querySellLimitOrders(
      productId: ProductId,
      timeInterval: TimeInterval
  ): Query0[SellLimitOrder] = {
    sql"""SELECT DISTINCT
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
          FROM orders
          WHERE time BETWEEN ${timeInterval.startTime.toString} and ${timeInterval.endTime.toString}
          AND product_id = ${productId.toString}
          AND order_type = ${OrderType.limit.name}
          AND side = ${OrderSide.sell.name}
          AND price > 0
          AND size > 0
       """
      .query[SellLimitOrder]
  }

  protected def querySellMarketOrders(
      productId: ProductId,
      timeInterval: TimeInterval
  ): Query0[SellMarketOrder] = {
    sql"""SELECT DISTINCT
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
          FROM orders
          WHERE time BETWEEN ${timeInterval.startTime.toString} and ${timeInterval.endTime.toString}
          AND product_id = ${productId.toString}
          AND order_type = ${OrderType.market.name}
          AND side = ${OrderSide.sell.name}
          AND size > 0
       """
      .query[SellMarketOrder]
  }
}

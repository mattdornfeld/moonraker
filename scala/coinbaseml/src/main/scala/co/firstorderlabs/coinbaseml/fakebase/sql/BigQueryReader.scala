package co.firstorderlabs.coinbaseml.fakebase.sql

import java.time.Instant

import co.firstorderlabs.coinbaseml.fakebase.sql.Implicits._
import co.firstorderlabs.coinbaseml.fakebase.sql.{Configs => SqlConfigs}
import co.firstorderlabs.common.protos.events.{BuyLimitOrder, BuyMarketOrder, Cancellation, DoneReason, OrderSide, RejectReason, SellLimitOrder, SellMarketOrder}
import co.firstorderlabs.common.protos.fakebase.OrderType
import co.firstorderlabs.common.types.Types._
import doobie.Query0
import doobie.implicits._
import doobie.util.transactor.Strategy

object BigQueryReader
    extends DatabaseReader(
      "com.simba.googlebigquery.jdbc42.Driver",
      "jdbc:bigquery://https://www.googleapis.com/bigquery/v2:443;" +
        "EnableHighThroughputAPI=1;" +
        "HighThroughputActivationRatio=1;" +
        "HighThroughputMinTableSize=1;" +
        "LogLevel=0;" +
        s"ProjectId=${SqlConfigs.gcpProjectId};" +
        "OAuthType=0;" +
        s"OAuthServiceAcctEmail=${SqlConfigs.serviceAccountEmail};" +
        s"OAuthPvtKeyPath=${SqlConfigs.serviceAccountJsonPath};" +
        s"DefaultDataset=${SqlConfigs.datasetId};" +
        s"Timeout=${SqlConfigs.queryTimeout}",
      "",
      "",
      Strategy.void
    ) {

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

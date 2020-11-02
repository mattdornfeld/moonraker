package co.firstorderlabs.coinbaseml.fakebase.sql

import java.time.{Duration, Instant}
import java.util.logging.Logger

import cats.effect.{Blocker, IO, Resource}
import co.firstorderlabs.coinbaseml.fakebase.{Configs, Snapshotable}
import co.firstorderlabs.common.currency.Configs.ProductPrice.productId
import co.firstorderlabs.common.protos.fakebase.{BuyLimitOrder, BuyMarketOrder, Cancellation, SellLimitOrder, SellMarketOrder}
import co.firstorderlabs.coinbaseml.fakebase.sql.Implicits._
import co.firstorderlabs.coinbaseml.fakebase.sql.{Configs => SqlConfigs}
import co.firstorderlabs.coinbaseml.fakebase.types.Events.Event
import co.firstorderlabs.coinbaseml.fakebase.types.Types._
import doobie.Query0
import doobie.hikari.HikariTransactor
import doobie.implicits._
import doobie.util.ExecutionContexts
import doobie.util.transactor.Strategy

import scala.collection.mutable
import scala.concurrent.ExecutionContext

abstract class DatabaseReaderBase(
    driverClassName: String,
    url: String,
    user: String,
    password: String,
    strategy: Strategy,
) extends Snapshotable[DatabaseReaderSnapshot] {
  protected val queryResultMap: mutable.AbstractMap[TimeInterval, QueryResult]
  private implicit val contextShift = IO.contextShift(ExecutionContext.global)
  val logger = Logger.getLogger(toString)

  protected val transactor =
    buildTransactor(driverClassName, url, user, password)

  private def buildTransactor(
      driverClassName: String,
      url: String,
      user: String,
      password: String
  ): Resource[IO, HikariTransactor[IO]] =
    for {
      executionContext <-
        ExecutionContexts.fixedThreadPool[IO](SqlConfigs.numDatabaseWorkers)
      blocker <- Blocker[IO]
      transactor <- HikariTransactor.newHikariTransactor[IO](
        driverClassName,
        url,
        user,
        password,
        executionContext,
        blocker
      )
    } yield transactor.copy(strategy0 = strategy)

  def clear: Unit

  def buildQueryResult(timeInterval: TimeInterval): QueryResult =
    QueryResult(
          queryBuyLimitOrders(productId, timeInterval).executeQuery,
          queryBuyMarketOrders(productId, timeInterval).executeQuery,
          queryCancellations(productId, timeInterval).executeQuery,
          querySellLimitOrders(productId, timeInterval).executeQuery,
          querySellMarketOrders(productId, timeInterval).executeQuery,
          timeInterval
        )

  def getQueryResult(timeInterval: TimeInterval): QueryResult

  def getResultMapSize: Int = queryResultMap.size

  protected def populateQueryResultMap(timeInterval: TimeInterval, timeDelta: Duration): Unit

  def start(startTime: Instant, endTime: Instant, timeDelta: Duration)

  protected def queryCancellations(
      productId: ProductId,
      timeInterval: TimeInterval
  ): Query0[Cancellation]

  protected def queryBuyLimitOrders(
      productId: ProductId,
      timeInterval: TimeInterval
  ): Query0[BuyLimitOrder]

  protected def  queryBuyMarketOrders(
      productId: ProductId,
      timeInterval: TimeInterval
  ): Query0[BuyMarketOrder]

  protected def querySellLimitOrders(
        productId: ProductId,
        timeInterval: TimeInterval
    ): Query0[SellLimitOrder]

  protected def querySellMarketOrders(
      productId: ProductId,
      timeInterval: TimeInterval
  ): Query0[SellMarketOrder]

  def queryResultMapKeys: Iterable[TimeInterval] = queryResultMap.keys

  protected implicit class QueryExecutor[A <: Event](query: Query0[A]) {
    def executeQuery: List[A] = {
      if (Configs.testMode) {
        List[A]()
      } else {
        transactor.use { xa =>
          query.stream.compile.toList
            .transact(xa)
        }.unsafeRunSync
      }
  }
  }
}

object DatabaseReaderBase {
  def clearAllReaders: Unit = {
    BigQueryReader.clear
    PostgresReader.clear
  }

  def areReadersCleared: Boolean = BigQueryReader.isCleared && PostgresReader.isCleared
}


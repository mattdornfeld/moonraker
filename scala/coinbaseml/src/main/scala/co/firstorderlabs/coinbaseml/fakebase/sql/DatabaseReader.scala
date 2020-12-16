package co.firstorderlabs.coinbaseml.fakebase.sql
import java.time.{Duration, Instant}
import java.util.logging.Logger

import cats.effect.IO.ioConcurrentEffect
import cats.effect.{Blocker, IO, Resource, Timer}
import co.firstorderlabs.coinbaseml.common.utils.Utils.ParallelSeq
import co.firstorderlabs.coinbaseml.fakebase.sql.Implicits._
import co.firstorderlabs.coinbaseml.fakebase.sql.{Configs => SqlConfigs}
import co.firstorderlabs.coinbaseml.fakebase.{Configs, Exchange, Snapshotable}
import co.firstorderlabs.common.currency.Configs.ProductPrice.productId
import co.firstorderlabs.common.protos.events.{BuyLimitOrder, BuyMarketOrder, Cancellation, SellLimitOrder, SellMarketOrder}
import co.firstorderlabs.common.types.Events.Event
import co.firstorderlabs.common.types.Types._
import doobie.Query0
import doobie.hikari.HikariTransactor
import doobie.implicits._
import doobie.util.ExecutionContexts
import doobie.util.transactor.Strategy
import fs2.{Pure, Stream}
import retry.RetryPolicies.{constantDelay, limitRetries}
import retry.{PolicyDecision, RetryDetails, RetryPolicy, retryingOnAllErrors}

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future, duration}

final class QueryResultMap(
    maxSize: Int,
    trieMap: Option[TrieMap[TimeInterval, QueryResult]] = None
) extends BoundedTrieMap[TimeInterval, QueryResult](maxSize, trieMap) {
  override def put(
      key: TimeInterval,
      value: QueryResult
  ): Option[QueryResult] = {
    val simulationMetadata = Exchange.getSimulationMetadata
    val currentTimeInterval = simulationMetadata.currentTimeInterval
    val timeDelta = simulationMetadata.timeDelta

    if (
      size < maxSize || currentTimeInterval.numStepsTo(
        key,
        timeDelta
      ) < SqlConfigs.queryResultMapMaxOverflow
    ) {
      _trieMap.put(key, value)
    } else {
      None
    }
  }
}

abstract class DatabaseReader(
    driverClassName: String,
    url: String,
    user: String,
    password: String,
    strategy: Strategy
) extends Snapshotable[DatabaseReaderSnapshot] {
  protected implicit val contextShift = IO.contextShift(ExecutionContext.global)
  private implicit val timer: Timer[IO] = IO.timer(ExecutionContext.global)

  protected val logger = Logger.getLogger(toString)
  protected val transactor =
    buildTransactor(driverClassName, url, user, password)
  protected val queryResultMap = new QueryResultMap(
    SqlConfigs.maxQueryResultMapSize
  )
  private val giveUpWhenStopped = RetryPolicy.lift[IO] { _ =>
    if (_shouldStop) {
      PolicyDecision.GiveUp
    } else {
      PolicyDecision.DelayAndRetry(duration.Duration.Zero)
    }
  }
  private val blockerResource = Blocker[IO]
  private val addRetryPolicy =
    constantDelay[IO](10.milliseconds) join
      giveUpWhenStopped
  private val removeRetryPolicyMaxRetries = 1000 * 60 * 5
  private val removeRetryPolicy =
    limitRetries[IO](1000 * 60 * 5) join
      constantDelay[IO](1.milliseconds) join
      giveUpWhenStopped
  private val interrupter =
    Stream.repeatEval(shouldStop).metered(10.millisecond)
  private var _shouldStop = false
  private var _streamFuture: Option[Future[Unit]] = None

  def buildQueryResult(timeInterval: TimeInterval): Option[QueryResult] = {
    if (Configs.testMode) {
      Some(QueryResult(List(), timeInterval))
    } else {
      val events: List[Event] =
        transactor.use { xa =>
          val stream = queryBuyLimitOrders(productId, timeInterval).stream ++
            queryBuyMarketOrders(productId, timeInterval).stream ++
            queryCancellations(productId, timeInterval).stream ++
            querySellLimitOrders(productId, timeInterval).stream ++
            querySellMarketOrders(productId, timeInterval).stream
          stream.compile.toList.transact(xa)
        }.unsafeRunSync
      Some(QueryResult(events.sortBy(_.time), timeInterval))
    }
  }

  def clear: Unit = {
    _shouldStop = true
    _streamFuture.map(Await.ready(_, 1.minute))
    queryResultMap.clear
    _shouldStop = false
  }

  def getResultMapSize: Int = queryResultMap.size

  def queryResultMapKeys: Iterable[TimeInterval] = queryResultMap.keys

  def removeQueryResult(timeInterval: TimeInterval): QueryResult = {
    retryingOnAllErrors[QueryResult](
      removeRetryPolicy,
      (throwable: Throwable, retryDetails: RetryDetails) =>
        IO {
          if (retryDetails.cumulativeDelay == 10.seconds) {
            logger.info(
              s"The QueryResult for ${timeInterval} was not found in queryResultMap. " +
                s"Waiting for it to be retrieved from LocalStorage."
            )
          } else if (retryDetails.retriesSoFar >= removeRetryPolicyMaxRetries) {
            throw throwable
          } else {}
        }
    )(
      removeFromQueryResultMap(timeInterval)
    ).unsafeRunSync
  }

  def start(
      startTime: Instant,
      endTime: Instant,
      timeDelta: Duration,
      backupToCloudStorage: Boolean = false,
  ): Unit = {
    clear
    val readFromDatabase: Stream[IO, Option[QueryResultSstFileWriter]] = TimeInterval(startTime, endTime)
      .chunkBy(SqlConfigs.bigQueryReadTimeDelta)
      .toStreams(SqlConfigs.numDatabaseReaderThreads)
      .map { stream: Stream[Pure, TimeInterval] =>
        stream.evalMap(timeInterval =>
          IO {
            populateLocalStorage(timeInterval, timeDelta, backupToCloudStorage)
          }
        )
      }
      .reduce(_ merge _)

    val sstFiles: Seq[QueryResultSstFileWriter] = readFromDatabase.compile.toList.unsafeRunSync.flatten
    if (sstFiles.size > 0) {
      LocalStorage.QueryResults.bulkIngest(sstFiles)
      LocalStorage.compact
    }

    startPopulateQueryResultMapStream(
      TimeInterval(startTime, endTime),
      timeDelta
    )
    logger.info(
      s"${getClass.getSimpleName} started for ${startTime}-${endTime} with timeDelta ${timeDelta}"
    )
  }

  def startPopulateQueryResultMapStream(
      timeInterval: TimeInterval,
      timeDelta: Duration
  ): Unit = {
    val stream = buildPopulateQueryResultMapStream(
      timeInterval.chunkBy(timeDelta)
    )
    _streamFuture = Some(stream.compile.drain.unsafeToFuture)
  }

  def streamFuture: Option[Future[Unit]] = _streamFuture

  override def createSnapshot: DatabaseReaderSnapshot = {
    val simulationMetaData = Exchange.getSimulationMetadata
    val timeInterval = TimeInterval(
      simulationMetaData.currentTimeInterval.startTime,
      simulationMetaData.endTime
    )
    DatabaseReaderSnapshot(timeInterval)
  }

  override def isCleared: Boolean =
    queryResultMap.isEmpty

  override def restore(snapshot: DatabaseReaderSnapshot): Unit = {
    val timeDelta = Exchange.getSimulationMetadata.timeDelta
    clear
    startPopulateQueryResultMapStream(snapshot.timeInterval, timeDelta)
  }

  private def addToQueryResultMap(
      timeInterval: TimeInterval,
      queryResult: QueryResult
  ): IO[QueryResult] =
    queryResultMap.put(timeInterval, queryResult) match {
      case Some(queryResult) => IO { queryResult }
      case None              => IO.raiseError(new IllegalAccessException)
    }

  private def buildPopulateQueryResultMapStream(
      timeIntervals: Seq[TimeInterval]
  ): Stream[IO, Unit] = {
    timeIntervals
      .toStreams(SqlConfigs.numLocalStorageReaderThreads)
      .map(stream =>
        stream.evalMap(timeInterval =>
          blockerResource.use { blocker =>
            blocker.blockOn { populateQueryResultMap(timeInterval) }
          }
        )
      )
      .reduce(_ merge _)
      .interruptWhen(interrupter)
  }

  private def loadQueryResultToMemory(
      timeInterval: TimeInterval
  ): IO[QueryResult] =
    LocalStorage.QueryResults.get(timeInterval) match {
      case Some(queryResult) => IO(queryResult)
      case None              => IO.raiseError(new NoSuchElementException)
    }

  private def populateLocalStorage(
      timeInterval: TimeInterval,
      timeDelta: Duration,
      backupToCloudStorage: Boolean,
  ): Option[QueryResultSstFileWriter] = {
    val queryHistoryKey = QueryHistoryKey(productId, timeInterval, timeDelta)
    if (LocalStorage.QueryHistory.contains(queryHistoryKey) && !SqlConfigs.forcePullFromDatabase) {
      logger.info(
        s"Data for ${queryHistoryKey} found locally. Skipping database query."
      )
      None
    } else if (!SqlConfigs.forcePullFromDatabase && CloudStorage.contains(queryHistoryKey)) {
      logger.info(
        s"Data for ${queryHistoryKey} found in cloud storage backup. Downloading and skipping database query."
      )
      CloudStorage.get(queryHistoryKey)
      Some(QueryResultSstFileWriter(queryHistoryKey))
    } else {
      logger.info(s"Querying database for events in ${queryHistoryKey}")

      val queryResultsSstFileWriter = new QueryResultSstFileWriter(
        queryHistoryKey
      )

      val queryResults = if (Configs.testMode) {
        QueryResult(List(), timeInterval).chunkByTimeDelta(timeDelta)
      } else {
        val events: List[Event] = transactor
          .use { xa =>
            val stream: Stream[doobie.ConnectionIO, Event] =
              queryBuyLimitOrders(productId, timeInterval).stream ++
                queryBuyMarketOrders(productId, timeInterval).stream ++
                queryCancellations(productId, timeInterval).stream ++
                querySellLimitOrders(productId, timeInterval).stream ++
                querySellMarketOrders(productId, timeInterval).stream

            stream.compile.toList.transact(xa)
          }
          .unsafeRunSync
          .sortBy(_.time)

        QueryResult(events, timeInterval).chunkByTimeDelta(timeDelta)
      }

      queryResultsSstFileWriter.addAll(
        queryResults
          .map(queryResult => (queryResult.timeInterval, queryResult))
          .iterator
      )
      queryResultsSstFileWriter.finish

      if (backupToCloudStorage) {
        logger.info(s"Backing up sst file for ${queryHistoryKey} to cloud storage")
        CloudStorage.put(queryHistoryKey, queryResultsSstFileWriter.sstFile)
      }

      val numEmptyTimeIntervals =
        queryResults.map(_.events.size).count(_ == 0)

      if (numEmptyTimeIntervals > 0) {
        logger.warning(
          s"There were ${numEmptyTimeIntervals} empty time intervals returned in this query"
        )
      }

      logger.info(
        s"Successfully wrote ${timeInterval.chunkBy(timeDelta).size} TimeInterval keys to LocalStorage"
      )

      Some(queryResultsSstFileWriter)
    }
  }

  private def populateQueryResultMap(timeInterval: TimeInterval): IO[Unit] = {
    for {
      queryResult <- retryingOnAllErrors[QueryResult](
        addRetryPolicy,
        (_: Throwable, retryDetails: RetryDetails) =>
          IO {
            if (retryDetails.cumulativeDelay == 10.seconds) {
              logger.info(
                s"Local storage does not contain QueryResult for ${timeInterval}. Waiting for it to become available."
              )
            }
          }
      )(
        loadQueryResultToMemory(timeInterval)
      )

      _ <- retryingOnAllErrors[QueryResult](
        addRetryPolicy,
        (_: Throwable, retryDetails: RetryDetails) =>
          IO {
            if (retryDetails.cumulativeDelay == 10.seconds) {
              logger.fine(
                s"Could not add QueryResult for ${timeInterval} to queryResultMap because it's full. Waiting for space to become available."
              )
            }
          }
      )(
        addToQueryResultMap(timeInterval, queryResult)
      )
    } yield ()
  }

  private def removeFromQueryResultMap(
      timeInterval: TimeInterval
  ): IO[QueryResult] = {
    queryResultMap.remove(timeInterval) match {
      case Some(queryResult) => IO { queryResult }
      case None              => IO.raiseError(new IllegalAccessException)
    }
  }

  protected def queryCancellations(
      productId: ProductId,
      timeInterval: TimeInterval
  ): Query0[Cancellation]

  protected def queryBuyLimitOrders(
      productId: ProductId,
      timeInterval: TimeInterval
  ): Query0[BuyLimitOrder]

  protected def queryBuyMarketOrders(
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

  private def buildTransactor(
      driverClassName: String,
      url: String,
      user: String,
      password: String
  ): Resource[IO, HikariTransactor[IO]] =
    for {
      executionContext <- ExecutionContexts.fixedThreadPool[IO](
        SqlConfigs.numDatabaseReaderThreads
      )
      blocker <- blockerResource
      transactor <- HikariTransactor.newHikariTransactor[IO](
        driverClassName,
        url,
        user,
        password,
        executionContext,
        blocker
      )
    } yield transactor.copy(strategy0 = strategy)

  private def shouldStop: IO[Boolean] = IO(_shouldStop)
}

object DatabaseReader {
  def clearAllReaders: Unit = {
    BigQueryReader.clear
    PostgresReader.clear
  }

  def areReadersCleared: Boolean =
    BigQueryReader.isCleared && PostgresReader.isCleared
}

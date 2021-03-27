package co.firstorderlabs.coinbaseml.fakebase.sql
import java.time.Duration
import java.util.logging.Logger
import cats.effect.IO.ioConcurrentEffect
import cats.effect.{Blocker, IO, Resource}
import co.firstorderlabs.coinbaseml.common.Configs.{logLevel, testMode}
import co.firstorderlabs.coinbaseml.common.utils.Utils.ParallelSeq
import co.firstorderlabs.coinbaseml.fakebase.sql.Implicits._
import co.firstorderlabs.coinbaseml.fakebase.sql.{Configs => SqlConfigs}
import co.firstorderlabs.coinbaseml.fakebase.{Configs, SimulationMetadata, SimulationState, State, StateCompanion}
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
  def put(
      key: TimeInterval,
      value: QueryResult
  )(implicit simulationMetadata: SimulationMetadata): Option[QueryResult] = {
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

final case class DatabaseReaderState(simulationTimeInterval: TimeInterval)
    extends State[DatabaseReaderState] {
  private var _shouldStop = false
  override val companion = DatabaseReaderState
  var streamFuture: Option[Future[Unit]] = None
  val queryResultMap = new QueryResultMap(SqlConfigs.maxQueryResultMapSize)
  val interrupter =
    Stream.repeatEval(shouldStop).metered(10.millisecond)
  private val giveUpWhenStopped = RetryPolicy.lift[IO] { _ =>
    if (_shouldStop) {
      PolicyDecision.GiveUp
    } else {
      PolicyDecision.DelayAndRetry(duration.Duration.Zero)
    }
  }
  val removeRetryPolicyMaxRetries = 1000 * 60 * 5
  val addRetryPolicy = constantDelay[IO](10.milliseconds) join giveUpWhenStopped
  val removeRetryPolicy = limitRetries[IO](
    removeRetryPolicyMaxRetries
  ) join constantDelay[IO](1.milliseconds) join giveUpWhenStopped

  override def createSnapshot(implicit
      simulationState: SimulationState
  ): DatabaseReaderState = {
    val snapshotSimulationTimeInterval = TimeInterval(
      simulationState.simulationMetadata.currentTimeInterval.startTime,
      simulationState.simulationMetadata.endTime
    )
    DatabaseReaderState(snapshotSimulationTimeInterval)
  }

  def getResultMapSize: Int = queryResultMap.size

  def queryResultMapKeys: Iterable[TimeInterval] = queryResultMap.keys

  def shouldStop: IO[Boolean] = IO(_shouldStop)

  def stop: Unit = {
    _shouldStop = true
    streamFuture.map(Await.ready(_, 1.minute))
  }
}

object DatabaseReaderState extends StateCompanion[DatabaseReaderState] {
  override def create(implicit
      simulationMetadata: SimulationMetadata
  ): DatabaseReaderState =
    DatabaseReaderState(
      TimeInterval(simulationMetadata.startTime, simulationMetadata.endTime)
    )

  override def fromSnapshot(
      snapshot: DatabaseReaderState
  ): DatabaseReaderState = {
    DatabaseReaderState(snapshot.simulationTimeInterval)
  }
}

abstract class DatabaseReader(
    driverClassName: String,
    url: String,
    user: String,
    password: String,
    strategy: Strategy
) {
  protected implicit val contextShift = IO.contextShift(ExecutionContext.global)
  protected val logger = Logger.getLogger(toString)
  logger.setLevel(logLevel)
  protected val transactor =
    buildTransactor(driverClassName, url, user, password)
  private val blockerResource = Blocker[IO]

  def buildQueryResult(timeInterval: TimeInterval): Option[QueryResult] = {
    if (testMode) {
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

  def createSstFiles(
      timeInterval: TimeInterval,
      timeDelta: Duration,
      backupToCloudStorage: Boolean
  )(implicit
      databaseReaderState: DatabaseReaderState
  ): Seq[QueryResultSstFileWriter] = {
    val createSstFiles: Stream[IO, Option[QueryResultSstFileWriter]] =
      timeInterval
        .chunkBy(SqlConfigs.bigQueryReadTimeDelta)
        .toStreams(SqlConfigs.numDatabaseReaderThreads)
        .map { stream: Stream[Pure, TimeInterval] =>
          stream.evalMap(subTimeInterval =>
            IO {
              createSstFile(
                subTimeInterval,
                timeDelta,
                backupToCloudStorage
              )
            }
          )
        }
        .reduce(_ merge _)

    createSstFiles.compile.toList.unsafeRunSync.flatten
  }

  def removeQueryResult(
      timeInterval: TimeInterval
  )(implicit databaseReaderState: DatabaseReaderState): QueryResult = {
    retryingOnAllErrors[QueryResult](
      databaseReaderState.removeRetryPolicy,
      (throwable: Throwable, retryDetails: RetryDetails) =>
        IO {
          if (retryDetails.cumulativeDelay == 5.seconds) {
            logger.info(
              s"The QueryResult for ${timeInterval} was not found in queryResultMap. " +
                s"Waiting for it to be retrieved from LocalStorage."
            )
          } else if (
            retryDetails.retriesSoFar >= databaseReaderState.removeRetryPolicyMaxRetries
          ) {
            throw throwable
          } else {}
        }
    )(
      removeFromQueryResultMap(timeInterval)
    ).unsafeRunSync
  }

  def start(skipDatabaseQuery: Boolean = false)(implicit
      databaseReaderState: DatabaseReaderState,
      simulationMetadata: SimulationMetadata
  ): Unit = {
    if (!skipDatabaseQuery) {
      val sstFileWriters =
        createSstFiles(
          databaseReaderState.simulationTimeInterval,
          simulationMetadata.timeDelta,
          simulationMetadata.backupToCloudStorage
        )
      if (sstFileWriters.size > 0) {
        LocalStorage.QueryResults.bulkIngest(sstFileWriters)
        LocalStorage.compact
      }
    }

    startPopulateQueryResultMapStream
    logger.fine(
      s"${getClass.getSimpleName} started for ${databaseReaderState.simulationTimeInterval.startTime}-" +
        s"${databaseReaderState.simulationTimeInterval.endTime} with timeDelta ${simulationMetadata.timeDelta}"
    )
  }

  def startPopulateQueryResultMapStream(implicit
      databaseReaderState: DatabaseReaderState,
      simulationMetadata: SimulationMetadata
  ): Unit = {
    val stream = buildPopulateQueryResultMapStream(
      databaseReaderState.simulationTimeInterval.chunkBy(
        simulationMetadata.timeDelta
      )
    )
    databaseReaderState.streamFuture = Some(stream.compile.drain.unsafeToFuture)
  }

  private def addToQueryResultMap(
      timeInterval: TimeInterval,
      queryResult: QueryResult
  )(implicit
      databaseReaderState: DatabaseReaderState,
      simulationMetadata: SimulationMetadata
  ): IO[QueryResult] =
    databaseReaderState.queryResultMap.put(timeInterval, queryResult) match {
      case Some(queryResult) => IO { queryResult }
      case None              => IO.raiseError(new IllegalAccessException)
    }

  private def buildPopulateQueryResultMapStream(
      timeIntervals: Seq[TimeInterval]
  )(implicit
      databaseReaderState: DatabaseReaderState,
      simulationMetadata: SimulationMetadata
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
      .interruptWhen(databaseReaderState.interrupter)
  }

  private def loadQueryResultToMemory(
      timeInterval: TimeInterval
  ): IO[QueryResult] =
    LocalStorage.QueryResults.get(timeInterval) match {
      case Some(queryResult) => IO(queryResult)
      case None              => IO.raiseError(new NoSuchElementException)
    }

  private def createSstFile(
      timeInterval: TimeInterval,
      timeDelta: Duration,
      backupToCloudStorage: Boolean
  ): Option[QueryResultSstFileWriter] = {
    val queryHistoryKey = QueryHistoryKey(productId, timeInterval, timeDelta)
    if (
      LocalStorage.QueryHistory.contains(
        queryHistoryKey
      ) && !SqlConfigs.forcePullFromDatabase
    ) {
      logger.info(
        s"Data for ${queryHistoryKey} found locally. Skipping database query."
      )
      None
    } else if (
      !SqlConfigs.forcePullFromDatabase && CloudStorage.contains(
        queryHistoryKey
      )
    ) {
      logger.info(
        s"Data for ${queryHistoryKey} found in cloud storage backup. Downloading and skipping database query."
      )
      CloudStorage.get(queryHistoryKey)
      Some(QueryResultSstFileWriter(queryHistoryKey, false))
    } else {
      logger.info(s"Querying database for events in ${queryHistoryKey}")

      val queryResultsSstFileWriter =
        QueryResultSstFileWriter(queryHistoryKey, true)

      val queryResults = if (testMode) {
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
        logger.info(
          s"Backing up sst file for ${queryHistoryKey} to cloud storage"
        )
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
        s"Successfully wrote ${timeInterval.chunkBy(timeDelta).size} TimeInterval keys to sst file"
      )

      Some(queryResultsSstFileWriter)
    }
  }

  private def populateQueryResultMap(
      timeInterval: TimeInterval
  )(implicit
      databaseReaderState: DatabaseReaderState,
      simulationMetadata: SimulationMetadata
  ): IO[Unit] = {
    for {
      queryResult <- retryingOnAllErrors[QueryResult](
        databaseReaderState.addRetryPolicy,
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
        databaseReaderState.addRetryPolicy,
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
  )(implicit databaseReaderState: DatabaseReaderState): IO[QueryResult] = {
    databaseReaderState.queryResultMap.remove(timeInterval) match {
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
}

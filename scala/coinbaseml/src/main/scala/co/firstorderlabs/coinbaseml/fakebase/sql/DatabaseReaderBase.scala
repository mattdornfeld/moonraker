package co.firstorderlabs.coinbaseml.fakebase.sql
import java.time.{Duration, Instant}
import java.util.logging.Logger

import cats.effect.{Blocker, IO, Resource}
import co.firstorderlabs.coinbaseml.fakebase.sql.Implicits._
import co.firstorderlabs.coinbaseml.fakebase.sql.{Configs => SqlConfigs}
import co.firstorderlabs.coinbaseml.fakebase.types.Exceptions.TimeoutExceeded
import co.firstorderlabs.coinbaseml.fakebase.{Configs, Exchange, Snapshotable}
import co.firstorderlabs.common.currency.Configs.ProductPrice.productId
import co.firstorderlabs.common.protos.events.{
  BuyLimitOrder,
  BuyMarketOrder,
  Cancellation,
  SellLimitOrder,
  SellMarketOrder
}
import co.firstorderlabs.common.types.Events.Event
import co.firstorderlabs.common.types.Types._
import doobie.Query0
import doobie.hikari.HikariTransactor
import doobie.implicits._
import doobie.util.ExecutionContexts
import doobie.util.transactor.Strategy

import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}

object DatabaseReaderThread {
  private val timeout = 500000

  /** Call f on params until return value is not empty. Wait sleepTime between calls.
    * If timeout is exceeded throw 5exception.
    *
    * @param f
    * @param params
    * @param sleepTime in ms
    * @tparam A
    * @tparam B
    * @throws
    * @return
    */
  @throws[TimeoutExceeded]
  def runUntilSuccess[A, B](
      f: A => Option[B],
      params: A,
      sleepTime: Int,
      thread: Option[DatabaseReaderThread] = None
  ): Option[B] = {
    val timeoutNano = timeout * 1000000L
    val startTime = System.nanoTime

    var result = f(params)
    while (result.isEmpty) {
      thread match {
        case Some(thread) => if (thread.shouldStop) return None
        case None         =>
      }

      if (System.nanoTime - startTime > timeoutNano) {
        throw TimeoutExceeded(
          s"Timeout ${timeout} ms exceeded when calling ${f} on ${params}"
        )
      }
      Thread.sleep(sleepTime)
      result = f(params)
    }
    Some(result.get)
  }
}

abstract case class DatabaseReaderThread(
    buildQueryResult: TimeInterval => Option[QueryResult],
    queryResultMap: mutable.Map[TimeInterval, QueryResult],
    timeIntervalQueue: LinkedBlockingQueue[TimeInterval]
) extends Thread {
  private var _shouldStop = false
  private val logger = Logger.getLogger(getClass.toString)
  setDaemon(true)

  def inState(expectedState: Thread.State): Boolean = {
    val threadState = synchronized {
      getState
    }

    threadState.compareTo(expectedState) == 0
  }

  def stopWorker: Unit =
    synchronized {
      _shouldStop = true
    }

  def startWorker: Unit =
    synchronized {
      _shouldStop = false
      notify
    }

  def shouldStop: Boolean =
    synchronized {
      _shouldStop
    }

  @tailrec
  final def blockUntil(expectedState: Thread.State): Boolean = {
    Thread.sleep(10)
    logger.fine(s"${getId} in recursive loop")
    if (inState(expectedState)) {
      true
    } else {
      blockUntil(expectedState)
    }
  }

  def synchronizedWait: Unit =
    synchronized {
      wait
    }

  override def run(): Unit = {
    while (true) {
      if (shouldStop) { synchronizedWait }

      val item: Option[(TimeInterval, QueryResult)] = for {
        timeInterval <- DatabaseReaderThread.runUntilSuccess(
          timeIntervalQueue.takeOrElse _,
          None,
          10,
          Some(this)
        )
        queryResult <- DatabaseReaderThread.runUntilSuccess(
          buildQueryResult,
          timeInterval,
          10,
          Some(this)
        )
      } yield (timeInterval, queryResult)

      item.map { item =>
        DatabaseReaderThread.runUntilSuccess(
          (queryResultMap.put _).tupled,
          item,
          10,
          Some(this)
        )

        logger.fine(
          s"retrieved data for timeInterval ${item._1}"
        )
      }
    }
  }
}

abstract class DatabaseReaderBase(
    driverClassName: String,
    url: String,
    user: String,
    password: String,
    strategy: Strategy
) extends Snapshotable[DatabaseReaderSnapshot] {
  protected val queryResultMap: mutable.Map[TimeInterval, QueryResult]
  protected val timeIntervalQueue: LinkedBlockingQueue[TimeInterval]
  protected val workers: Seq[DatabaseReaderThread]
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
        ExecutionContexts.fixedThreadPool[IO](SqlConfigs.numDatabaseReaderThreads)
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

  override def createSnapshot: DatabaseReaderSnapshot = {
    val checkpointTimeIntervalQueue = new LinkedBlockingQueue[TimeInterval]
    populateTimeIntervalQueue(
      Exchange.getSimulationMetadata.currentTimeInterval.startTime,
      Exchange.getSimulationMetadata.endTime,
      Exchange.getSimulationMetadata.timeDelta,
      checkpointTimeIntervalQueue
    )
    DatabaseReaderSnapshot(checkpointTimeIntervalQueue)
  }

  private def populateTimeIntervalQueue(
      startTime: Instant,
      endTime: Instant,
      timeDelta: Duration,
      timeIntervalQueue: LinkedBlockingQueue[TimeInterval]
  ): Unit = {
    val timeIntervals =
      TimeInterval(startTime, endTime).chunkBy(timeDelta)
    timeIntervals.foreach(timeInterval => timeIntervalQueue.put(timeInterval))
  }

  def stopWorkers: Unit = {
    workers.foreach(w => w.stopWorker)
    blockUntilWaiting
  }

  private def startWorkers: Unit = {
    workers.foreach(w => w.startWorker)
  }

  def getQueryResult(timeInterval: TimeInterval): QueryResult =
    DatabaseReaderThread
      .runUntilSuccess(queryResultMap.remove _, timeInterval, 1)
      .get

  def inState(expectedState: Thread.State): Boolean =
    workers.forall(w => w.inState(expectedState))

  def isWaiting: Boolean = inState(Thread.State.WAITING)

  def blockUntilWaiting: Boolean = blockUntil(Thread.State.WAITING)

  def blockUntil(expectedState: Thread.State): Boolean = {
    workers.forall(w => w.blockUntil(expectedState))
  }

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

  def getResultMapSize: Int = queryResultMap.size

  def clear: Unit = {
    stopWorkers
    timeIntervalQueue.clear
    queryResultMap.clear
  }

  override def isCleared: Boolean = {
    (queryResultMap.isEmpty
    && timeIntervalQueue.isEmpty)
  }

  override def restore(snapshot: DatabaseReaderSnapshot): Unit = {
    clear
    timeIntervalQueue.addAll(snapshot.timeIntervalQueue)
    startWorkers
  }

  def start(
      startTime: Instant,
      endTime: Instant,
      timeDelta: Duration
  ): Future[Unit] = {
    clear
    populateTimeIntervalQueue(startTime, endTime, timeDelta, timeIntervalQueue)
    startWorkers
    logger.info(
      s"DatabaseWorkers started for ${startTime}-${endTime} with timeDelta ${timeDelta}"
    )
    Future {}
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

  def queryResultMapKeys: Iterable[TimeInterval] = queryResultMap.keys
}

object DatabaseReaderBase {
  def clearAllReaders: Unit = {
    BigQueryReader.clear
    PostgresReader.clear
  }

  def areReadersCleared: Boolean =
    BigQueryReader.isCleared && PostgresReader.isCleared
}

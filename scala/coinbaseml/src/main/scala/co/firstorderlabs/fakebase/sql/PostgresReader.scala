package co.firstorderlabs.fakebase.sql

import java.time.{Duration, Instant}

import co.firstorderlabs.fakebase.Exchange
import co.firstorderlabs.fakebase.protos.fakebase._
import co.firstorderlabs.fakebase.sql.Implicits._
import co.firstorderlabs.fakebase.sql.{Configs => SqlConfigs}
import co.firstorderlabs.fakebase.types.Exceptions.TimeoutExceeded
import co.firstorderlabs.fakebase.types.Types._
import doobie.Query0
import doobie.implicits._
import doobie.util.transactor.Strategy

import scala.annotation.tailrec

class PostgresReader extends Thread {
  private var paused = false

  def isPaused: Boolean = {
    val threadState = synchronized { getState }
    threadState.compareTo(Thread.State.WAITING) == 0 ||
    threadState.compareTo(Thread.State.TIMED_WAITING) == 0
  }

  def pauseWorker: Unit =
    synchronized {
      paused = true
    }

  def unpauseWorker: Unit =
    synchronized {
      paused = false
      notify
    }

  @tailrec
  final def waitUntilPaused: Boolean = {
    Thread.sleep(10)
    PostgresReader.logger.fine(s"${getId} in recursive loop")
    if (isPaused) {
      true
    } else {
      waitUntilPaused
    }
  }

  override def run(): Unit = populateQueryResultMap

  @tailrec
  private def populateQueryResultMap: Unit = {
    if (paused) synchronized { wait }
    if (!PostgresReader.timeIntervalQueue.isEmpty) {
      val timeInterval = PostgresReader.timeIntervalQueue.take
      PostgresReader.populateQueryResultMap(timeInterval, Duration.ZERO)
      PostgresReader.logger.fine(
        s"retrieved data for timeInterval ${timeInterval}"
      )
    } else {
      Thread.sleep(10)
    }
    populateQueryResultMap
  }
}

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
  private val timeIntervalQueue = new LinkedBlockingQueue[TimeInterval]
  private val workers =
    for (_ <- (1 to SqlConfigs.numDatabaseWorkers))
      yield new PostgresReader
  workers.foreach(w => w.start)
  private val timeoutMilli = 100000

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

  override def clear: Unit = {
    pauseWorkers
    queryResultMap.clear
    timeIntervalQueue.clear
    // It's possible some workers to be stuck in a call to queryResultMap.put, which will be released after the first
    // call to queryResultMap.clear. So we call it a second time here to remove any results those calls may have added
    // to queryResultMap
    waitUntilPaused
    queryResultMap.clear
  }

  override def isCleared: Boolean = {
    (queryResultMap.isEmpty
    && timeIntervalQueue.isEmpty)
  }

  override def restore(snapshot: DatabaseReaderSnapshot): Unit = {
    clear
    timeIntervalQueue.addAll(snapshot.timeIntervalQueue)
    unpauseWorkers
  }

  /** Call f on params until return value is not empty. Wait sleepTime between calls.
    * If timeout is exceeded throw 5exception.
    *
    * @param f
    * @param params
    * @param sleepTime
    * @tparam A
    * @throws
    * @return
    */
  @throws[TimeoutExceeded]
  def runUntilSuccess[A](f: A => Option[QueryResult], params: A, sleepTime: Int): QueryResult = {
    val timeoutNano = timeoutMilli * 1000000L
    val startTime = System.nanoTime
    var result = f(params)
    while (result.isEmpty) {
      if (System.nanoTime - startTime > timeoutNano) {
        throw TimeoutExceeded(s"Timeout ${timeoutMilli} ms exceeded when calling ${f} on ${params}")
      }
      Thread.sleep(sleepTime)
      result = f(params)
    }
    result.get
  }

  def getQueryResult(timeInterval: TimeInterval): QueryResult =
    runUntilSuccess(queryResultMap.remove _, timeInterval, 1)

  def isPaused: Boolean =
    workers.forall(w => w.isPaused)

  def waitUntilPaused: Boolean =
    workers.forall(w => w.waitUntilPaused)

  def start(startTime: Instant, endTime: Instant, timeDelta: Duration): Unit = {
    pauseWorkers
    clear
    populateTimeIntervalQueue(startTime, endTime, timeDelta, timeIntervalQueue)
    unpauseWorkers
    logger.info(
      s"DatabaseWorkers started for ${startTime}-${endTime} with timeDelta ${timeDelta}"
    )
  }

  private def pauseWorkers: Unit = {
    workers.foreach(w => w.pauseWorker)
    waitUntilPaused
  }

  private def unpauseWorkers: Unit = {
    workers.foreach(w => w.unpauseWorker)
  }

  protected def populateQueryResultMap(
      timeInterval: TimeInterval,
      timeDelta: Duration
  ): Unit = {
    val queryResult = buildQueryResult(timeInterval)

    logger.fine(s"Retrieved query results for time interval ${timeInterval}")

    runUntilSuccess((queryResultMap.put _).tupled, (timeInterval, queryResult), 10)
  }

  private def populateTimeIntervalQueue(
      startTime: Instant,
      endTime: Instant,
      timeDelta: Duration,
      timeIntervalQueue: LinkedBlockingQueue[TimeInterval]
  ): Unit = {
    val timeIntervals = TimeInterval(startTime, endTime).chunkByTimeDelta(timeDelta)
    timeIntervals.foreach(timeInterval => timeIntervalQueue.put(timeInterval))
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

  def setQueryResultMapMaxSize(maxSize: Int): Unit = queryResultMap.maxSize = maxSize

  def timeIntervalQueueElements: List[TimeInterval] = timeIntervalQueue.toArray.toList.asInstanceOf[List[TimeInterval]]


  def timeIntervalQueueSize: Int = timeIntervalQueue.size
}

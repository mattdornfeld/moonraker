package co.firstorderlabs.coinbaseml.fakebase.sql

import java.time.{Duration, Instant}

import co.firstorderlabs.coinbaseml.fakebase.Exchange
import co.firstorderlabs.coinbaseml.fakebase.protos.fakebase.OrderType
import co.firstorderlabs.coinbaseml.fakebase.sql.Implicits._
import co.firstorderlabs.coinbaseml.fakebase.sql.{Configs => SqlConfigs}
import co.firstorderlabs.coinbaseml.fakebase.types.Exceptions.TimeoutExceeded
import co.firstorderlabs.common.protos.events.{BuyLimitOrder, BuyMarketOrder, Cancellation, DoneReason, OrderSide, RejectReason, SellLimitOrder, SellMarketOrder}
import co.firstorderlabs.common.types.Types.{ProductId, TimeInterval}
import doobie.implicits._
import doobie.util.query.Query0
import doobie.util.transactor.Strategy

import scala.annotation.tailrec

class PostgresReader extends Thread {
  private var _shouldStop = false

  def inState(expectedState: Thread.State): Boolean = {
    val threadState = synchronized {
      getState
    }

    threadState.compareTo(expectedState) == 0
  }

  def stopWorker: Unit = synchronized {
      _shouldStop = true
    }

  def startWorker: Unit = synchronized {
      _shouldStop = false
      notify
    }

  def shouldStop: Boolean = synchronized {
      _shouldStop
    }

  @tailrec
  final def blockUntil(expectedState: Thread.State): Boolean = {
    Thread.sleep(10)
    PostgresReader.logger.fine(s"${getId} in recursive loop")
    if (inState(expectedState)) {
      true
    } else {
      blockUntil(expectedState)
    }
  }

  def synchronizedWait: Unit = synchronized {
      wait
    }

  override def run(): Unit = {
    while (true) {
      if (shouldStop) { synchronizedWait }

      val timeInterval = PostgresReader.runUntilSuccess(
        PostgresReader.timeIntervalQueue.takeOrElse _,
        None,
        10,
        Some(this)
      )

      timeInterval match {
        case None =>
        case Some(timeInterval) => {
          val queryResult = PostgresReader.buildQueryResult(timeInterval)

          PostgresReader.runUntilSuccess(
            (PostgresReader.queryResultMap.put _).tupled,
            (timeInterval, queryResult),
            10,
            Some(this)
          )

          PostgresReader.logger.fine(
            s"retrieved data for timeInterval ${timeInterval}"
          )
        }
      }
    }
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
    stopWorkers
    queryResultMap.clear
    timeIntervalQueue.clear
    // It's possible some workers to be stuck in a call to queryResultMap.put, which will be released after the first
    // call to queryResultMap.clear. So we call it a second time here to remove any results those calls may have added
    // to queryResultMap
//    blockUntilWaiting
//    queryResultMap.clear
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

  /** Call f on params until return value is not empty. Wait sleepTime between calls.
    * If timeout is exceeded throw 5exception.
    *
    * @param f
    * @param params
    * @param sleepTime
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
      thread: Option[PostgresReader] = None
  ): Option[B] = {
    val timeoutNano = timeoutMilli * 1000000L
    val startTime = System.nanoTime
    var result = f(params)
    while (result.isEmpty) {
      thread match {
        case Some(thread) => if (thread.shouldStop) return None
        case None =>
      }

      if (System.nanoTime - startTime > timeoutNano) {
        throw TimeoutExceeded(
          s"Timeout ${timeoutMilli} ms exceeded when calling ${f} on ${params}"
        )
      }
      Thread.sleep(sleepTime)
      result = f(params)
    }
    Some(result.get)
  }

  def getQueryResult(timeInterval: TimeInterval): QueryResult =
    runUntilSuccess(queryResultMap.remove _, timeInterval, 1).get

  def inState(expectedState: Thread.State): Boolean =
    workers.forall(w => w.inState(expectedState))

  def isWaiting: Boolean = inState(Thread.State.WAITING)

  def blockUntilWaiting: Boolean = blockUntil(Thread.State.WAITING)

  def blockUntil(expectedState: Thread.State): Boolean = {
    workers.forall(w => w.blockUntil(expectedState))
  }

  def start(startTime: Instant, endTime: Instant, timeDelta: Duration): Unit = {
    stopWorkers
    clear
    populateTimeIntervalQueue(startTime, endTime, timeDelta, timeIntervalQueue)
    startWorkers
    logger.info(
      s"DatabaseWorkers started for ${startTime}-${endTime} with timeDelta ${timeDelta}"
    )
  }

  private def stopWorkers: Unit = {
    workers.foreach(w => w.stopWorker)
    blockUntilWaiting
  }

  private def startWorkers: Unit = {
    workers.foreach(w => w.startWorker)
  }

  private def populateTimeIntervalQueue(
      startTime: Instant,
      endTime: Instant,
      timeDelta: Duration,
      timeIntervalQueue: LinkedBlockingQueue[TimeInterval]
  ): Unit = {
    val timeIntervals =
      TimeInterval(startTime, endTime).chunkByTimeDelta(timeDelta)
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

  def setQueryResultMapMaxSize(maxSize: Int): Unit =
    queryResultMap.maxSize = maxSize

  def timeIntervalQueueElements: List[TimeInterval] =
    timeIntervalQueue.toArray.toList.asInstanceOf[List[TimeInterval]]

  def timeIntervalQueueSize: Int = timeIntervalQueue.size
}

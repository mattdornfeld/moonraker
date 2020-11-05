package co.firstorderlabs.bqwriter

import java.time.Instant
import java.util
import java.util.Map.Entry
import java.util.logging.Logger
import java.util.{Map => JavaMap}

import co.firstorderlabs.coinbaseml.fakebase.protos.fakebase.OrderType
import co.firstorderlabs.common.currency.Configs.ProductPrice
import co.firstorderlabs.common.currency.Price.BtcUsdPrice.{
  ProductVolume,
  QuoteVolume
}
import co.firstorderlabs.common.protos.events.{
  BuyLimitOrder,
  BuyMarketOrder,
  Cancellation,
  DoneReason,
  Liquidity,
  Match,
  OrderSide,
  OrderStatus,
  SellLimitOrder,
  SellMarketOrder
}
import co.firstorderlabs.common.types.Events.{Event, OrderEvent}
import co.firstorderlabs.common.types.Types.{OrderId, ProductId, TradeId}
import com.google.cloud.bigquery.{BigQueryOptions, InsertAllRequest, TableId}
import info.bitrich.xchangestream.coinbasepro.dto.CoinbaseProWebSocketTransaction
import io.reactivex.Observer
import io.reactivex.disposables.Disposable

/**
  * Tracks the sequenceIds obtained from an exchange websocket feed.
  * Used to ensure that duplicate events aren't written to BQ and for keeping
  * track of number of dropped events.
  */
object SequenceTracker {
  private val sequenceIdBuffer = new util.LinkedHashMap[Long, Boolean] {
    var maxSize = Configs.maxSequenceTrackerSize
    override def removeEldestEntry(eldest: Entry[Long, Boolean]): Boolean = {
      size > maxSize
    }
  }
  private var _minSequenceId = Long.MaxValue
  private var _maxSequenceId = 0L
  private var _numSequenceIds = 0L
  private var _previousNumMissing = 0L
  private var _currentNumMissing = 0L

  /**
    * @param sequenceIds
    */
  def addAll(sequenceIds: Seq[Long]): Unit =
    sequenceIds.foreach(SequenceTracker.add(_))

  /**
    * Add sequenceId. Note that only maxSize will be stored in the buffer
    * but aggregates will represent the entire history. The buffer is FIFO.
    *
    * @param sequenceId
    */
  def add(sequenceId: Long): Unit = {
    if (!sequenceIdBuffer.containsKey(sequenceId)) {
      _numSequenceIds += 1
      if (sequenceId > _maxSequenceId) {
        _maxSequenceId = sequenceId
      }
      if (sequenceId < _minSequenceId) {
        _minSequenceId = sequenceId
      }
      sequenceIdBuffer.put(sequenceId, true)
    }

    val numExpected = _maxSequenceId - _minSequenceId
    _previousNumMissing = _currentNumMissing
    _currentNumMissing = numExpected - _numSequenceIds
  }

  /**
    * Resets state
    */
  def clear: Unit = {
    sequenceIdBuffer.clear
    _minSequenceId = Long.MaxValue
    _maxSequenceId = 0L
    _numSequenceIds = 0L
    _previousNumMissing = 0L
    _currentNumMissing = 0L
  }

  /**
    * Check if buffer contains sequenceId. Note that only maxSize will be stored
    * in the buffer. The buffer is FIFO.
    *
    * @param sequenceId
    * @return
    */
  def bufferContains(sequenceId: Long): Boolean =
    sequenceIdBuffer.containsKey(sequenceId)

  /**
    * Max sequenceId of sequenceId history
    *
    * @return
    */
  def max: Long = _maxSequenceId

  /**
    * Min sequenceId of sequenceId history
    *
    * @return
    */
  def min: Long = _minSequenceId

  /**
    * @return
    */
  def maxBufferSize: Int = sequenceIdBuffer.maxSize

  /**
    * @param maxSize
    */
  def setMaxBufferSize(maxSize: Int): Unit =
    sequenceIdBuffer.maxSize = maxSize

  /**
    * Number of sequenceIds received in history
    *
    * @return
    */
  def numReceived: Long = _numSequenceIds

  /**
    * Number of sequenceIds missing from history
    *
    * @return
    */
  def numMissing: Long = _currentNumMissing

  /**
    * Change in numMissing after last call to `add`.
    * This quantity is most useful for metrics.
    *
    * @return
    */
  def numMissingDelta: Long =
    _currentNumMissing - _previousNumMissing
}

class BigQueryRows(tableId: TableId) {
  private val builder = InsertAllRequest.newBuilder(tableId)
  private val logger = Logger.getLogger(this.getClass.toString)
  private var _numRows = 0

  def addRow(content: JavaMap[String, Any], sequenceId: Long): Unit = {
    if (!SequenceTracker.bufferContains(sequenceId)) {
      builder.addRow(content)
      SequenceTracker.add(sequenceId)
      _numRows += 1
    }
  }

  def build: InsertAllRequest = builder.build

  def writeToBigQuery: Unit = {
    if (numRows > 0) {
      val response = BigQueryRows.bigquery.insertAll(builder.build)

      logger.info(
        s"Wrote ${numRows} rows to table ${tableId.getTable} from channel ${Configs.channelId}."
      )

      if (response.hasErrors) {
        response.getInsertErrors.values.forEach(e => logger.severe(e.toString))
      }
    }
  }

  def numRows: Int = _numRows
}

object BigQueryRows {
  val bigquery = BigQueryOptions.newBuilder
    .setCredentials(Configs.serviceAccountJsonPath)
    .setProjectId(Configs.gcpProjectId)
    .build
    .getService
}

class BigQueryEventsWriter {
  val cancellationEvents = new BigQueryRows(
    BigQueryEventsWriter.cancellations
  )
  val matchEvents = new BigQueryRows(BigQueryEventsWriter.matches)
  val orderEvents = new BigQueryRows(BigQueryEventsWriter.orders)
  private val logger = Logger.getLogger(this.getClass.toString)
  private var _maxEventBufferSize = Configs.maxEventBufferSize

  def addEvent[A <: Event](event: Option[A], sequenceId: Long): Unit = {
    event.map { event =>
      event match {
        case event: Cancellation =>
          cancellationEvents.addRow(event.toBigQueryRow, sequenceId)
        case event: Match =>
          matchEvents.addRow(event.toBigQueryRow, sequenceId)
        case event: OrderEvent =>
          orderEvents.addRow(event.toBigQueryRow, sequenceId)
      }
    }

    if (event.isEmpty) {
      SequenceTracker.add(sequenceId)
    }
  }

  def shouldWrite: Boolean = numEvents >= _maxEventBufferSize

  def maxEventBufferSize: Int = _maxEventBufferSize

  def setMaxEventBufferSize(size: Int): Unit = _maxEventBufferSize = size

  def numEvents: Int =
    cancellationEvents.numRows + matchEvents.numRows + orderEvents.numRows

  def writeEventsToBigQuery: Unit = {
    if (!Configs.testMode) {
      cancellationEvents.writeToBigQuery
      matchEvents.writeToBigQuery
      orderEvents.writeToBigQuery
    }
    logger.info(
      s"Missing ${SequenceTracker.numMissingDelta} sequenceIds from channel ${Configs.channelId}."
    )
  }
}

object BigQueryEventsWriter {
  val cancellations: TableId = TableId.of(Configs.datasetId, "cancellations")
  val matches = TableId.of(Configs.datasetId, "matches")
  val orders = TableId.of(Configs.datasetId, "orders")
}

object EventTypes {
  val DONE = OrderStatus.done.name
  val MATCH = "match"
  val RECEIVED = OrderStatus.received.name
}

class EventObserver extends Observer[CoinbaseProWebSocketTransaction] {
  private val logger = Logger.getLogger(this.getClass.toString)
  var bigQueryEventsWriter = new BigQueryEventsWriter

  override def onSubscribe(d: Disposable): Unit = {
    logger.info(s"Subscribed to channel ${Configs.channelId}")
  }

  override def onNext(t: CoinbaseProWebSocketTransaction): Unit = {
    if (bigQueryEventsWriter.shouldWrite) {
      bigQueryEventsWriter.writeEventsToBigQuery
      bigQueryEventsWriter = new BigQueryEventsWriter
    }

    val event: Option[Event] =
      (t.getType, t.getOrderType, t.getSide, t.getReason) match {
        case (EventTypes.MATCH, _, _, _) => {
          if (
            List(
              t.getMakerOrderId,
              t.getPrice,
              t.getProductId,
              t.getSide,
              t.getSize,
              t.getTakerOrderId,
              t.getTime
            ).contains(null)
            || t.getTradeId == 0L
          ) {
            None
          } else {
            Some(
              Match(
                Liquidity.global,
                OrderId(t.getMakerOrderId),
                new ProductPrice(Left(t.getPrice)),
                ProductId.fromString(t.getProductId),
                OrderSide.fromName(t.getSide).get,
                new ProductVolume(Left(t.getSize)),
                OrderId(t.getTakerOrderId),
                Instant.parse(t.getTime),
                TradeId(t.getTradeId)
              )
            )
          }

        }

        case (
              EventTypes.RECEIVED,
              OrderType.limit.name,
              OrderSide.buy.name,
              _
            ) => {
          if (
            List(t.getOrderId, t.getPrice, t.getProductId, t.getSize, t.getTime)
              .contains(null)
          ) { None }
          else {
            Some(
              BuyLimitOrder(
                OrderId(t.getOrderId),
                OrderStatus.received,
                new ProductPrice(Left(t.getPrice)),
                ProductId.fromString(t.getProductId),
                OrderSide.buy,
                new ProductVolume(Left(t.getSize)),
                Instant.parse(t.getTime)
              )
            )
          }
        }

        case (
              EventTypes.RECEIVED,
              OrderType.market.name,
              OrderSide.buy.name,
              _
            ) => {
          if (
            List(t.getSize, t.getOrderId, t.getProductId, t.getTime).contains(
              null
            )
          ) { None }
          else {
            Some(
              BuyMarketOrder(
                new QuoteVolume(Left(t.getSize)),
                OrderId(t.getOrderId),
                OrderStatus.received,
                ProductId.fromString(t.getProductId),
                OrderSide.buy,
                Instant.parse(t.getTime)
              )
            )
          }
        }

        case (
              EventTypes.RECEIVED,
              OrderType.limit.name,
              OrderSide.sell.name,
              _
            ) => {
          if (
            List(t.getOrderId, t.getPrice, t.getProductId, t.getSize, t.getTime)
              .contains(null)
          ) { None }
          else {
            Some(
              SellLimitOrder(
                OrderId(t.getOrderId),
                OrderStatus.received,
                new ProductPrice(Left(t.getPrice)),
                ProductId.fromString(t.getProductId),
                OrderSide.sell,
                new ProductVolume(Left(t.getSize)),
                Instant.parse(t.getTime)
              )
            )

          }

        }

        case (
              EventTypes.RECEIVED,
              OrderType.market.name,
              OrderSide.sell.name,
              _
            ) => {
          if (
            List(t.getOrderId, t.getProductId, t.getSize, t.getTime).contains(
              null
            )
          ) { None }
          else {
            Some(
              SellMarketOrder(
                OrderId(t.getOrderId),
                OrderStatus.received,
                ProductId.fromString(t.getProductId),
                OrderSide.buy,
                new ProductVolume(Left(t.getSize)),
                Instant.parse(t.getTime)
              )
            )
          }
        }

        case (EventTypes.DONE, _, _, DoneReason.canceled.name) => {
          if (
            List(
              t.getOrderId,
              t.getPrice,
              t.getProductId,
              t.getSide,
              t.getRemainingSize,
              t.getTime
            ).contains(null)
          ) { None }
          else {
            Some(
              Cancellation(
                OrderId(t.getOrderId),
                new ProductPrice(Left(t.getPrice)),
                ProductId.fromString(t.getProductId),
                OrderSide.fromName(t.getSide).get,
                new ProductVolume(Left(t.getRemainingSize)),
                Instant.parse(t.getTime)
              )
            )
          }
        }

        case _ => None
      }

    bigQueryEventsWriter.addEvent(event, t.getSequence)
  }

  override def onError(e: Throwable): Unit =
    logger.severe(e.getStackTrace.toString)

  override def onComplete(): Unit = {
    logger.info(s"Disconnecting from channel ${Configs.channelId}")
  }
}

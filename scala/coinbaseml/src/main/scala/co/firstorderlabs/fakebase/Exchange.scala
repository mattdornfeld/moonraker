package co.firstorderlabs.fakebase

import java.time.{Duration, Instant}
import java.util.logging.Logger

import co.firstorderlabs.common.utils.Utils.getResultOptional
import co.firstorderlabs.fakebase.currency.Configs.ProductPrice.{
  ProductVolume,
  QuoteVolume
}
import co.firstorderlabs.fakebase.protos.fakebase._
import co.firstorderlabs.fakebase.types.Events.{
  Event,
  LimitOrderEvent,
  OrderEvent
}
import co.firstorderlabs.fakebase.types.Exceptions.SimulationNotStarted
import co.firstorderlabs.fakebase.types.Types.TimeInterval
import com.google.protobuf.empty.Empty

import scala.concurrent.Future

case class ExchangeSnapshot(receivedEvents: List[Event]) extends Snapshot

case class SimulationMetadata(startTime: Instant,
                              endTime: Instant,
                              timeDelta: Duration,
                              numWarmUpSteps: Int,
                              initialProductFunds: ProductVolume,
                              initialQuoteFunds: QuoteVolume) {
  var currentTimeInterval =
    TimeInterval(startTime.minus(timeDelta), startTime)

  def incrementCurrentTimeInterval: Unit = {
    currentTimeInterval = currentTimeInterval + timeDelta
  }

  def previousTimeInterval: TimeInterval = {
    currentTimeInterval - timeDelta
  }

  def simulationIsOver: Boolean =
    currentTimeInterval.startTime isAfter endTime
}

object Exchange
    extends ExchangeServiceGrpc.ExchangeService
    with Snapshotable[ExchangeSnapshot] {
  private val logger = Logger.getLogger(Exchange.toString)
  private val matchingEngine = MatchingEngine

  var simulationMetadata: Option[SimulationMetadata] = None
  private var receivedEvents: List[Event] = List()

  def cancelOrder(order: OrderEvent): OrderEvent =
    matchingEngine.cancelOrder(order)

  def checkIsTaker(limitOrder: LimitOrderEvent): Boolean = {
    matchingEngine.checkIsTaker(limitOrder)
  }

  override def createSnapshot: ExchangeSnapshot = {
    ExchangeSnapshot(receivedEvents)
  }

  override def clear: Unit = {
    receivedEvents = List()
  }

  override def isCleared: Boolean = {
    receivedEvents.isEmpty
  }

  override def restore(snapshot: ExchangeSnapshot): Unit = {
    // calling clear is not necessary since receivedEvents is an immutable List
    // and we're replacing the entire var, not the list contents
    receivedEvents = snapshot.receivedEvents
  }

  def getOrderBook(side: OrderSide): OrderBook = {
    matchingEngine.orderBooks(side)
  }

  @throws[SimulationNotStarted]
  def getSimulationMetadata: SimulationMetadata = {
    simulationMetadata match {
      case Some(simulationMetadata) => simulationMetadata
      case None =>
        throw SimulationNotStarted(
          "The field simulationMetadata is empty. Please start a simulation."
        )
    }
  }

  override def checkpoint(request: Empty): Future[Empty] = {
    logger.info(
      s"creating checkpoint at timeInterval ${Exchange.getSimulationMetadata.currentTimeInterval}"
    )
    Checkpointer.createCheckpoint
    Future.successful(Constants.emptyProto)
  }

  override def getExchangeInfo(
    exchangeInfoRequest: ExchangeInfoRequest
  ): Future[ExchangeInfo] = {
    Future.successful(
      getExchangeInfoHelper(exchangeInfoRequest.orderBooksRequest)
    )
  }

  override def getMatches(request: Empty): Future[MatchEvents] = {
    Future.successful(MatchEvents(matchingEngine.matches.toList))
  }

  override def getOrderBooks(request: OrderBooksRequest): Future[OrderBooks] = {
    val buyOrderBook = MatchingEngine
      .orderBooks(OrderSide.buy)
      .aggregateToMap(request.orderBookDepth, true)

    val sellOrderBook = MatchingEngine
      .orderBooks(OrderSide.sell)
      .aggregateToMap(request.orderBookDepth)

    val orderBooks = new OrderBooks(buyOrderBook, sellOrderBook)
    Future.successful(orderBooks)
  }

  override def reset(
    exchangeInfoRequest: ExchangeInfoRequest
  ): Future[ExchangeInfo] = {
    getSimulationMetadata.currentTimeInterval =
      Checkpointer.checkpointTimeInterval
    Checkpointer.restoreFromCheckpoint
    logger.info(
      s"simulation reset to timeInterval ${getSimulationMetadata.currentTimeInterval}"
    )

    getExchangeInfo(exchangeInfoRequest)
  }

  override def start(
    simulationStartRequest: SimulationStartRequest
  ): Future[ExchangeInfo] = {
    require(
      simulationStartRequest.snapshotBufferSize > 0,
      "snapshotBufferSize must be greater than 0"
    )
    if (simulationInProgress) stop(Constants.emptyProto)

    simulationMetadata = Some(
      SimulationMetadata(
        simulationStartRequest.startTime,
        simulationStartRequest.endTime,
        simulationStartRequest.timeDelta.get,
        simulationStartRequest.numWarmUpSteps,
        simulationStartRequest.initialProductFunds,
        simulationStartRequest.initialQuoteFunds
      )
    )

    logger.info(s"starting simulation for parameters ${getSimulationMetadata}")

    DatabaseWorkers.start(
      getSimulationMetadata.startTime,
      getSimulationMetadata.endTime,
      getSimulationMetadata.timeDelta
    )

    Account.initializeWallets
    Account.addFunds(simulationStartRequest.initialQuoteFunds)
    Account.addFunds(simulationStartRequest.initialProductFunds)

    SnapshotBuffer.start(simulationStartRequest.snapshotBufferSize)

    if (simulationStartRequest.numWarmUpSteps > 0) {
      (1 to simulationStartRequest.numWarmUpSteps) foreach (
        _ => step(Constants.emptyStepRequest)
      )
      checkpoint(Constants.emptyProto)
    }

    val exchangeInfoRequest =
      if (simulationStartRequest.exchangeInfoRequest.isDefined)
        simulationStartRequest.exchangeInfoRequest.get
      else ExchangeInfoRequest()
    getExchangeInfo(exchangeInfoRequest)
  }

  override def step(stepRequest: StepRequest): Future[ExchangeInfo] = {
    getSimulationMetadata.incrementCurrentTimeInterval
    require(
      !getSimulationMetadata.simulationIsOver,
      "The simulation has ended. Please reset."
    )

    logger.fine(
      s"Stepped to time interval ${getSimulationMetadata.currentTimeInterval}"
    )
    logger.fine(
      s"There are ${DatabaseWorkers.getResultMapSize.toString} entries in the results map queue"
    )

    Account.step

    val queryResult =
      DatabaseWorkers.getQueryResult(getSimulationMetadata.currentTimeInterval)

    receivedEvents = (Account.getReceivedOrders.toList
      ++ Account.getReceivedCancellations.toList
      ++ stepRequest.insertOrders
        .map(OrderUtils.orderEventFromSealedOneOf)
        .flatten
      ++ stepRequest.insertCancellations
      ++ queryResult.buyLimitOrders
      ++ queryResult.buyMarketOrders
      ++ queryResult.sellLimitOrders
      ++ queryResult.sellMarketOrder
      ++ queryResult.cancellations)
      .sortBy(event => event.time)

    if (receivedEvents.isEmpty)
      logger.warning(
        s"No events queried for time interval ${getSimulationMetadata.currentTimeInterval}"
      )
    else
      logger.fine(s"Processing ${receivedEvents.length} events")

    matchingEngine.matches.clear
    matchingEngine.processEvents(receivedEvents)

    SnapshotBuffer.step

    val exchangeInfoRequest =
      if (stepRequest.exchangeInfoRequest.isDefined)
        stepRequest.exchangeInfoRequest.get
      else ExchangeInfoRequest()
    getExchangeInfo(exchangeInfoRequest)
  }

  override def stop(request: Empty): Future[Empty] = {
    Checkpointer.clear
    simulationMetadata = None
    logger.info("simulation stopped")
    Future.successful(Constants.emptyProto)
  }

  private def getExchangeInfoHelper(
    orderBooksRequest: Option[OrderBooksRequest]
  ): ExchangeInfo = {
    if (simulationMetadata.isDefined) {
      val orderBooks =
        if (orderBooksRequest.isDefined)
          getResultOptional(getOrderBooks(orderBooksRequest.get))
        else None

      ExchangeInfo(
        getSimulationMetadata.currentTimeInterval.startTime,
        getSimulationMetadata.currentTimeInterval.endTime,
        getResultOptional(Account.getAccountInfo(Constants.emptyProto)),
        orderBooks,
        getResultOptional(getMatches(Constants.emptyProto)),
      )
    } else {
      ExchangeInfo()
    }
  }

  private def simulationInProgress: Boolean = simulationMetadata.isDefined
}

package co.firstorderlabs.fakebase

import java.time.Duration
import java.util.logging.Logger

import co.firstorderlabs.fakebase.Utils.getResultOptional
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
import co.firstorderlabs.fakebase.types.Types.{Datetime, TimeInterval}
import com.google.protobuf.empty.Empty

import scala.concurrent.Future

case class ExchangeCheckpoint(receivedEvents: List[Event]) extends Checkpoint

case class SimulationMetadata(startTime: Datetime,
                              endTime: Datetime,
                              timeDelta: Duration,
                              numWarmUpSteps: Int,
                              initialProductFunds: ProductVolume,
                              initialQuoteFunds: QuoteVolume) {
  var currentTimeInterval =
    TimeInterval(Datetime(startTime.instant.minus(timeDelta)), startTime)
  var checkpoint: Option[SimulationCheckpoint] = None

  def checkpointTimeInterval: TimeInterval = {
    val checkpointStartTime =
      startTime.instant.plus(timeDelta.multipliedBy(numWarmUpSteps))
    val checkpointEndTime =
      startTime.instant.plus(timeDelta.multipliedBy(numWarmUpSteps + 1))
    TimeInterval(Datetime(checkpointStartTime), Datetime(checkpointEndTime))
  }

  def incrementCurrentTimeInterval: Unit = {
    currentTimeInterval = currentTimeInterval + timeDelta
  }

  def simulationIsOver: Boolean =
    currentTimeInterval.startTime.instant isAfter endTime.instant
}

object Exchange
    extends ExchangeServiceGrpc.ExchangeService
    with Checkpointable[ExchangeCheckpoint] {
  private val logger = Logger.getLogger(Exchange.toString)
  private val matchingEngine = MatchingEngine

  var simulationMetadata: Option[SimulationMetadata] = None
  private var receivedEvents: List[Event] = List()

  def cancelOrder(order: OrderEvent): OrderEvent =
    matchingEngine.cancelOrder(order)

  def checkIsTaker(limitOrder: LimitOrderEvent): Boolean = {
    matchingEngine.checkIsTaker(limitOrder)
  }

  def checkpoint: ExchangeCheckpoint = {
    ExchangeCheckpoint(receivedEvents)
  }

  def clear: Unit = {
    receivedEvents = List()
  }

  def isCleared: Boolean = {
    receivedEvents.isEmpty
  }

  def restore(checkpoint: ExchangeCheckpoint): Unit = {
    // calling clear is not necessary since receivedEvents is an immutable List
    // and we're replacing the entire var, not the list contents
    receivedEvents = checkpoint.receivedEvents
  }

  def getOrderBook(side: OrderSide): OrderBook = {
    matchingEngine.orderBooks(side)
  }

  override def checkpoint(request: Empty): Future[Empty] = {
    simulationMetadata.get.checkpoint = Some(Checkpointer.createCheckpoint)
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
    val buyOrderBook = for ((price, volume) <- MatchingEngine
                              .orderBooks(OrderSide.buy)
                              .aggregateToMap(request.orderBookDepth, true))
      yield (price.toPlainString, volume.toPlainString)

    val sellOrderBook = for ((price, volume) <- MatchingEngine
                               .orderBooks(OrderSide.sell)
                               .aggregateToMap(request.orderBookDepth))
      yield (price.toPlainString, volume.toPlainString)

    val orderBooks = new OrderBooks(buyOrderBook, sellOrderBook)
    Future.successful(orderBooks)
  }

  override def reset(
    exchangeInfoRequest: ExchangeInfoRequest
  ): Future[ExchangeInfo] = {
    failIfNoSimulationInProgress
    simulationMetadata.get.currentTimeInterval =
      simulationMetadata.get.checkpointTimeInterval
    Checkpointer.restoreFromCheckpoint(simulationMetadata.get.checkpoint.get)
    logger.info(
      s"simulation reset to timeInterval ${simulationMetadata.get.currentTimeInterval}"
    )

    getExchangeInfo(exchangeInfoRequest)
  }

  override def start(
    simulationStartRequest: SimulationStartRequest
  ): Future[ExchangeInfo] = {
    if (simulationInProgress) stop(Constants.emptyProto)

    simulationMetadata = Some(
      SimulationMetadata(
        simulationStartRequest.startTime,
        simulationStartRequest.endTime,
        Duration.ofSeconds(simulationStartRequest.timeDelta.get.seconds),
        simulationStartRequest.numWarmUpSteps,
        simulationStartRequest.initialProductFunds,
        simulationStartRequest.initialQuoteFunds
      )
    )

    logger.info(s"starting simulation for parameters ${simulationMetadata.get}")

    DatabaseWorkers.start(
      simulationMetadata.get.startTime,
      simulationMetadata.get.endTime,
      simulationMetadata.get.timeDelta
    )

    Account.initializeWallets
    Account.addFunds(simulationStartRequest.initialQuoteFunds)
    Account.addFunds(simulationStartRequest.initialProductFunds)

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
    failIfNoSimulationInProgress
    simulationMetadata.get.incrementCurrentTimeInterval
    require(
      !simulationMetadata.get.simulationIsOver,
      "The simulation has ended. Please reset."
    )

    logger.fine(
      s"Stepped to time interval ${simulationMetadata.get.currentTimeInterval}"
    )
    logger.fine(
      s"There are ${DatabaseWorkers.getResultMapSize.toString} entries in the results map queue"
    )

    Account.step

    val queryResult =
      DatabaseWorkers.getQueryResult(simulationMetadata.get.currentTimeInterval)

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
      .sortBy(event => event.time.instant)

    if (receivedEvents.isEmpty)
      logger.warning(
        s"No events queried for time interval ${simulationMetadata.get.currentTimeInterval}"
      )
    else
      logger.fine(s"Processing ${receivedEvents.length} events")

    matchingEngine.matches.clear
    matchingEngine.processEvents(receivedEvents)

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
        simulationMetadata.get.currentTimeInterval.startTime,
        simulationMetadata.get.currentTimeInterval.endTime,
        getResultOptional(Account.getAccountInfo(Constants.emptyProto)),
        orderBooks,
        getResultOptional(getMatches(Constants.emptyProto)),
      )
    } else {
      ExchangeInfo()
    }
  }

  private def failIfNoSimulationInProgress: Unit =
    require(simulationInProgress, "no simulation in progress")

  private def simulationInProgress: Boolean = simulationMetadata.isDefined
}

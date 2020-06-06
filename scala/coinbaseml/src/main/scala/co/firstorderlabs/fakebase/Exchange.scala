package co.firstorderlabs.fakebase

import java.time.Duration
import java.util.logging.Logger

import co.firstorderlabs.fakebase.Account.Account
import co.firstorderlabs.fakebase.currency.Configs.ProductPrice.{ProductVolume, QuoteVolume}
import co.firstorderlabs.fakebase.protos.fakebase._
import co.firstorderlabs.fakebase.types.Events.{Event, LimitOrderEvent, OrderEvent}
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

  def restore(checkpoint: ExchangeCheckpoint): Unit = {
    // calling clear is not necessary since receivedEvents is an immutable List
    // and we're replacing the entire var, not the list contents
    receivedEvents = checkpoint.receivedEvents
  }

  def getOrderBook(side: OrderSide): OrderBook = {
    matchingEngine.orderBooks(side)
  }

  override def getExchangeInfo(request: Empty): Future[ExchangeInfo] = Future.successful(getExchangeInfo)

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

  override def reset(request: Empty): Future[ExchangeInfo] = {
    failIfNoSimulationInProgress
    simulationMetadata.get.currentTimeInterval =
      simulationMetadata.get.checkpointTimeInterval
    Checkpointer.restoreFromCheckpoint(simulationMetadata.get.checkpoint.get)
    logger.info(s"simulation reset to timeInterval ${simulationMetadata.get.currentTimeInterval}")
    Future.successful(getExchangeInfo)
  }

  override def start(request: SimulationStartRequest): Future[ExchangeInfo] = {
    require(!simulationInProgress, "simulation is already in progress")

    simulationMetadata = Some(
      SimulationMetadata(
        request.startTime,
        request.endTime,
        Duration.ofSeconds(request.timeDelta.get.seconds),
        request.numWarmUpSteps,
        request.initialProductFunds,
        request.initialQuoteFunds
      )
    )

    logger.info(s"starting simulation for parameters ${simulationMetadata.get}")

    DatabaseWorkers.start(
      simulationMetadata.get.startTime,
      simulationMetadata.get.endTime,
      simulationMetadata.get.timeDelta
    )

    Account.initializeWallets
    Account.addFunds(request.initialQuoteFunds)
    Account.addFunds(request.initialProductFunds)
    (1 to request.numWarmUpSteps) foreach (_ => step(Configs.emptyProto))

    simulationMetadata.get.checkpoint = Some(Checkpointer.createCheckpoint)

    Future.successful(getExchangeInfo)
  }

  override def step(request: Empty): Future[ExchangeInfo] = {
    failIfNoSimulationInProgress
    simulationMetadata.get.incrementCurrentTimeInterval
    require(
      !simulationMetadata.get.simulationIsOver,
      "The simulation has ended. Please reset."
    )

    logger.info(
      s"Stepped to time interval ${simulationMetadata.get.currentTimeInterval}"
    )
    logger.info(
      s"There are ${DatabaseWorkers.getResultMapSize.toString} entries in the results map queue"
    )

    Account.step

    val queryResult =
      DatabaseWorkers.getQueryResult(simulationMetadata.get.currentTimeInterval)
    receivedEvents = (Account.getReceivedOrders.toList
      ++ Account.getReceivedCancellations.toList
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
      logger.info(s"Processing ${receivedEvents.length} events")

    matchingEngine.matches.clear
    matchingEngine.processEvents(receivedEvents)

    Future.successful(getExchangeInfo)
  }

  override def stop(request: Empty): Future[Empty] = {
    failIfNoSimulationInProgress
    Checkpointer.clear
    simulationMetadata = None
    logger.info("simulation stopped")
    Future.successful(Configs.emptyProto)
  }

  private def getExchangeInfo: ExchangeInfo = {
    if (simulationMetadata.isDefined)
      ExchangeInfo(
        simulationMetadata.get.currentTimeInterval.startTime,
        simulationMetadata.get.currentTimeInterval.endTime
      )
    else
      ExchangeInfo()
  }

  private def failIfNoSimulationInProgress: Unit =
    require(simulationInProgress, "no simulation in progress")

  private def simulationInProgress: Boolean = simulationMetadata.isDefined
}

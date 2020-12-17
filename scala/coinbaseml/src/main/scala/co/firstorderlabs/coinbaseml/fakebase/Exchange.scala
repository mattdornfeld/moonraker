package co.firstorderlabs.coinbaseml.fakebase

import java.time.{Duration, Instant}
import java.util.UUID.randomUUID
import java.util.logging.Logger

import co.firstorderlabs.coinbaseml.common.utils.Utils.{
  getResult,
  getResultOptional
}
import co.firstorderlabs.coinbaseml.common.{Environment, InfoAggregator}
import co.firstorderlabs.coinbaseml.fakebase.sql.{
  BigQueryReader,
  DatabaseReader,
  LocalStorage,
  PostgresReader
}
import co.firstorderlabs.coinbaseml.fakebase.types.Exceptions.{
  SimulationNotStarted,
  SnapshotBufferNotFull
}
import co.firstorderlabs.coinbaseml.fakebase.utils.OrderUtils
import co.firstorderlabs.common.currency.Configs.ProductPrice.{
  ProductVolume,
  QuoteVolume
}
import co.firstorderlabs.common.protos.environment.ObservationRequest
import co.firstorderlabs.common.protos.events.{MatchEvents, OrderSide}
import co.firstorderlabs.common.protos.fakebase.DatabaseBackend.{
  BigQuery,
  Postgres
}
import co.firstorderlabs.common.protos.fakebase._
import co.firstorderlabs.common.protos.{events, fakebase}
import co.firstorderlabs.common.types.Events.{
  Event,
  LimitOrderEvent,
  OrderEvent
}
import co.firstorderlabs.common.types.Types.TimeInterval
import com.google.protobuf.empty.Empty

import scala.concurrent.Future

final case class ExchangeSnapshot(receivedEvents: List[Event]) extends Snapshot

final case class SimulationMetadata(
    startTime: Instant,
    endTime: Instant,
    timeDelta: Duration,
    numWarmUpSteps: Int,
    initialProductFunds: ProductVolume,
    initialQuoteFunds: QuoteVolume,
    simulationId: String,
    observationRequest: ObservationRequest,
    enableProgressBar: Boolean,
    simulationType: SimulationType,
    databaseReader: DatabaseReader,
    snapshotBufferSize: Int
) {
  var currentTimeInterval =
    TimeInterval(startTime.minus(timeDelta), startTime)
  var currentStep = 0L

  private val progressBar: Option[StepProgressLogger] =
    if (enableProgressBar)
      Some(
        new StepProgressLogger(
          s"${simulationType.name} simulation ${simulationId} progress",
          numSteps
        )
      )
    else None

  def incrementCurrentTimeInterval: Unit =
    currentTimeInterval = currentTimeInterval + timeDelta

  def numSteps: Long =
    Duration.between(startTime, endTime).dividedBy(timeDelta)

  def previousTimeInterval: TimeInterval = {
    currentTimeInterval - timeDelta
  }

  def reset: Unit = {
    currentTimeInterval = Checkpointer.checkpointTimeInterval
    currentStep = numWarmUpSteps
    progressBar match {
      case Some(progressBar) => progressBar.resetTo(numWarmUpSteps)
      case None              =>
    }
  }

  def step(
      stepDuration: Double,
      dataGetDuration: Double,
      matchingEngineDuration: Double,
      environmentDuration: Double,
      numEvents: Int
  ): Unit = {
    currentStep += 1
    progressBar match {
      case Some(progressBar) =>
        progressBar.step(
          currentStep,
          stepDuration,
          dataGetDuration,
          matchingEngineDuration,
          environmentDuration,
          numEvents
        )
      case None =>
    }
  }

  def simulationIsOver: Boolean =
    currentTimeInterval.endTime isAfter endTime
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

  def getReceivedEvents: List[Event] = receivedEvents

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

  @throws[SnapshotBufferNotFull]
  override def checkpoint(request: Empty): Future[Empty] = {
    logger.info(
      s"creating checkpoint at timeInterval ${Exchange.getSimulationMetadata.currentTimeInterval}"
    )
    Checkpointer.createCheckpoint
    Future.successful(Constants.emptyProto)
  }

  override def getExchangeInfo(
      request: Empty
  ): Future[ExchangeInfo] = {
    Future.successful(
      getExchangeInfoHelper
    )
  }

  override def getMatches(request: Empty): Future[MatchEvents] = {
    Future.successful(events.MatchEvents(matchingEngine.matches.toList))
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

  override def populateStorage(
      populateStorageRequest: PopulateStorageRequest
  ): Future[Empty] = {
    require(
      populateStorageRequest.ingestToLocalStorage || populateStorageRequest.backupToCloudStorage,
      "Either ingestToLocalStorage or backupToCloudStorage must be true."
    )
    val databaseReader = getDatabaseReader(populateStorageRequest.databaseBackend)
    val sstFileWriters = populateStorageRequest.populateStorageParameters.map {
      populateStorageParameter =>
        val timeInterval = TimeInterval(
          populateStorageParameter.startTime,
          populateStorageParameter.endTime
        )
        databaseReader.createSstFiles(
          timeInterval,
          populateStorageParameter.timeDelta.get,
          populateStorageRequest.backupToCloudStorage
        )
    }.flatten

    if (populateStorageRequest.ingestToLocalStorage) {
      LocalStorage.QueryResults.bulkIngest(sstFileWriters)
    }
    Future.successful(Constants.emptyProto)
  }

  override def reset(
      simulationInfoRequest: SimulationInfoRequest
  ): Future[SimulationInfo] = {
    val simulationMetadata = getSimulationMetadata
    logger.info(
      s"Resetting simulation ${simulationMetadata.simulationId} to ${simulationMetadata.currentTimeInterval}"
    )
    getSimulationMetadata.reset
    Checkpointer.restoreFromCheckpoint

    Future successful getSimulationInfo(Some(simulationInfoRequest))
  }

  @throws[IllegalArgumentException]
  override def start(
      simulationStartRequest: SimulationStartRequest
  ): Future[SimulationInfo] = {
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
        simulationStartRequest.initialQuoteFunds,
        randomUUID.toString,
        simulationStartRequest.observationRequest.get,
        simulationStartRequest.enableProgressBar,
        simulationStartRequest.simulationType,
        getDatabaseReader(simulationStartRequest.databaseBackend),
        simulationStartRequest.snapshotBufferSize
      )
    )

    logger.info(
      s"starting simulation ${simulationMetadata.get.simulationId} for parameters ${simulationMetadata.get}"
    )
    Checkpointer.start
    Environment.start(simulationStartRequest.snapshotBufferSize)
    getSimulationMetadata.databaseReader.start(
      getSimulationMetadata.startTime,
      getSimulationMetadata.endTime,
      getSimulationMetadata.timeDelta,
      simulationStartRequest.backupToCloudStorage
    )

    Account.initializeWallets
    Account.addFunds(simulationStartRequest.initialQuoteFunds)
    Account.addFunds(simulationStartRequest.initialProductFunds)
    MatchingEngine.start

    if (simulationStartRequest.numWarmUpSteps > 0) {
      (1 to simulationStartRequest.numWarmUpSteps) foreach (_ =>
        step(Constants.emptyStepRequest)
      )
      checkpoint(Constants.emptyProto)
    }

    Future successful getSimulationInfo(
      simulationStartRequest.simulationInfoRequest
    )
  }

  override def step(stepRequest: StepRequest): Future[SimulationInfo] = {
    val stepStartTime = System.nanoTime
    getSimulationMetadata.incrementCurrentTimeInterval
    require(
      !getSimulationMetadata.simulationIsOver,
      "The simulation has ended. Please reset."
    )

    logger.fine(
      s"Stepped to ${getSimulationMetadata.currentTimeInterval}"
    )

    Account.step
    Environment.preStep(stepRequest.actionRequest)
    InfoAggregator.preStep

    val dataGetStartTime = System.nanoTime
    val queryResult = Exchange.getSimulationMetadata.databaseReader
      .removeQueryResult(getSimulationMetadata.currentTimeInterval)

    receivedEvents = (Account.getReceivedOrders.toList
      ++ Account.getReceivedCancellations.toList
      ++ stepRequest.insertOrders
        .map(OrderUtils.orderEventFromSealedOneOf)
        .flatten
      ++ stepRequest.insertCancellations
      ++ queryResult.events)
      .sortBy(event => event.time)

    val dataGetDuration = (System.nanoTime - dataGetStartTime) / 1e6

    if (receivedEvents.isEmpty)
      logger.warning(
        s"No events queried for time interval ${getSimulationMetadata.currentTimeInterval}"
      )
    else
      logger.fine(s"Processing ${receivedEvents.length} events")

    matchingEngine.matches.clear

    val matchingEngineStartTime = System.nanoTime
    matchingEngine.step(receivedEvents)
    val matchingEngineDuration =
      (System.nanoTime - matchingEngineStartTime) / 1e6

    val environmentStartTime = System.nanoTime
    Environment.step
    val environmentDuration = (System.nanoTime - environmentStartTime) / 1e6

    val stepDuration = (System.nanoTime - stepStartTime) / 1e6
    InfoAggregator.step(
      stepDuration,
      dataGetDuration,
      matchingEngineDuration,
      environmentDuration,
      receivedEvents.size
    )

    val simulationInfo = getSimulationInfo(stepRequest.simulationInfoRequest)

    logger.fine(s"Exchange.step took ${stepDuration} ms")
    getSimulationMetadata.step(
      stepDuration,
      dataGetDuration,
      matchingEngineDuration,
      environmentDuration,
      receivedEvents.size
    )

    Future successful simulationInfo
  }

  override def stop(request: Empty): Future[Empty] = {
    logger.info("stopping simulation")
    Checkpointer.clear
    simulationMetadata = None
    Future.successful(Constants.emptyProto)
  }

  private def getDatabaseReader(
      databaseBackend: DatabaseBackend
  ): DatabaseReader =
    databaseBackend match {
      case Postgres => PostgresReader
      case BigQuery => BigQueryReader
      case _ =>
        throw new IllegalArgumentException(
          s"${databaseBackend} is not a valid instance of DatabaseBackend"
        )
    }

  private def getExchangeInfoHelper: ExchangeInfo = {
    if (simulationMetadata.isDefined) {
      fakebase.ExchangeInfo(
        getSimulationMetadata.currentTimeInterval.startTime,
        getSimulationMetadata.currentTimeInterval.endTime,
        getResultOptional(Account.getAccountInfo(Constants.emptyProto)),
        getSimulationMetadata.simulationId
      )
    } else {
      ExchangeInfo()
    }
  }

  def getSimulationInfo(
      simulationInfoRequest: Option[SimulationInfoRequest]
  ): SimulationInfo = {
    if (simulationInfoRequest.isDefined) {
      val _simulationInfoRequest = simulationInfoRequest.get
      val exchangeInfo = getExchangeInfoHelper
      val observation = _simulationInfoRequest.observationRequest match {
        case Some(observationRequest) =>
          Some(getResult(Environment.getObservation(observationRequest)))
        case None => None
      }

      fakebase.SimulationInfo(Some(exchangeInfo), observation)
    } else {
      SimulationInfo()
    }
  }

  private def simulationInProgress: Boolean = simulationMetadata.isDefined
}

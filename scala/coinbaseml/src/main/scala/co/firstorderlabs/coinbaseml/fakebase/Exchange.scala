package co.firstorderlabs.coinbaseml.fakebase

import co.firstorderlabs.coinbaseml.common.Configs.logLevel
import co.firstorderlabs.coinbaseml.common.actions.actionizers.Actionizer

import java.util.logging.Logger
import co.firstorderlabs.coinbaseml.common.utils.Utils.{getResult, getResultOptional}
import co.firstorderlabs.coinbaseml.common.{Environment, InfoAggregator}
import co.firstorderlabs.coinbaseml.fakebase.sql._
import co.firstorderlabs.coinbaseml.fakebase.utils.OrderUtils
import co.firstorderlabs.common.protos.environment.ObservationRequest
import co.firstorderlabs.common.protos.events.MatchEvents
import co.firstorderlabs.common.protos.fakebase.DatabaseBackend.{BigQuery, Postgres}
import co.firstorderlabs.common.protos.fakebase._
import co.firstorderlabs.common.protos.{events, fakebase}
import co.firstorderlabs.common.types.Actionizers.ActionizerConfigs
import co.firstorderlabs.common.types.Events.{Event, LimitOrderEvent, OrderEvent}
import co.firstorderlabs.common.types.Types.{SimulationId, TimeInterval}
import co.firstorderlabs.common.types.Utils.OptionUtils
import com.google.protobuf.empty.Empty

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

final case class ExchangeState(var receivedEvents: List[Event])
    extends State[ExchangeState] {
  override val companion = ExchangeState
  override def createSnapshot(implicit
      simulationState: SimulationState
  ): ExchangeState = {
    ExchangeState(receivedEvents)
  }

}

object ExchangeState extends StateCompanion[ExchangeState] {
  override def create(implicit
      simulationMetadata: SimulationMetadata
  ): ExchangeState =
    ExchangeState(List())

  override def fromSnapshot(snapshot: ExchangeState): ExchangeState =
    ExchangeState(snapshot.receivedEvents)

}

object Exchange extends ExchangeServiceGrpc.ExchangeService {
  private val logger = Logger.getLogger(Exchange.toString)
  logger.setLevel(logLevel)
  private val matchingEngine = MatchingEngine

  def cancelOrder(order: OrderEvent)(implicit
      accountState: AccountState,
      matchingEngineState: MatchingEngineState,
      simulationMetadata: SimulationMetadata
  ): OrderEvent = {
    implicit val orderBookState =
      if (order.side.isbuy) matchingEngineState.buyOrderBookState
      else matchingEngineState.sellOrderBookState
    matchingEngine.cancelOrder(order)
  }

  def checkIsTaker(
      limitOrder: LimitOrderEvent
  )(implicit matchingEngineState: MatchingEngineState): Boolean = {
    matchingEngine.checkIsTaker(limitOrder)
  }

  def getReceivedEvents(implicit exchangeState: ExchangeState): List[Event] =
    exchangeState.receivedEvents

  override def checkpoint(simulationId: SimulationId): Future[Empty] = {
    logger.info(
      s"creating checkpoint at timeInterval ${SimulationState.getSimulationMetadataOrFail(simulationId).currentTimeInterval}"
    )
    implicit val simulationState = SimulationState.getOrFail(simulationId)
    SimulationState.snapshot(simulationId)
    Future.successful(Constants.emptyProto)
  }

  override def getExchangeInfo(
      simulationId: SimulationId
  ): Future[ExchangeInfo] = {
    Future.successful(
      getExchangeInfoHelper(simulationId)
    )
  }

  override def getMatches(simulationId: SimulationId): Future[MatchEvents] = {
    val matchingEngineState = SimulationState
      .getOrFail(
        simulationId
      )
      .matchingEngineState
    Future.successful(events.MatchEvents(matchingEngineState.matches.toList))
  }

  override def getOrderBooks(
      orderBooksRequest: OrderBooksRequest
  ): Future[OrderBooks] = {
    val matchingEngineState = SimulationState
      .getOrFail(
        orderBooksRequest.simulationId.get
      )
      .matchingEngineState
    val buyOrderBook = OrderBook
      .aggregateToMap(orderBooksRequest.orderBookDepth, true)(
        matchingEngineState.buyOrderBookState
      )

    val sellOrderBook = OrderBook
      .aggregateToMap(orderBooksRequest.orderBookDepth)(
        matchingEngineState.sellOrderBookState
      )

    val orderBooks = new OrderBooks(buyOrderBook, sellOrderBook)
    Future.successful(orderBooks)
  }

  override def getSimulationIds(request: Empty): Future[SimulationIds] = {
    Future.successful(SimulationIds(SimulationState.keys))
  }

  override def populateStorage(
      populateStorageRequest: PopulateStorageRequest
  ): Future[Empty] = {
    require(
      populateStorageRequest.ingestToLocalStorage || populateStorageRequest.backupToCloudStorage,
      "Either ingestToLocalStorage or backupToCloudStorage must be true."
    )
    val databaseReader = getDatabaseReader(
      populateStorageRequest.databaseBackend
    )
    val sstFileWriters = populateStorageRequest.populateStorageParameters.map {
      populateStorageParameter =>
        val timeInterval = TimeInterval(
          populateStorageParameter.startTime,
          populateStorageParameter.endTime
        )
        implicit val databaseReaderState = DatabaseReaderState(timeInterval)
        databaseReader.createSstFiles(
          timeInterval,
          populateStorageParameter.timeDelta.get,
          populateStorageRequest.backupToCloudStorage
        )
    }.flatten

    if (
      populateStorageRequest.ingestToLocalStorage && sstFileWriters.size > 0
    ) {
      LocalStorage.QueryResults.bulkIngest(sstFileWriters)
    }
    Future.successful(Constants.emptyProto)
  }

  override def reset(
      observationRequest: ObservationRequest
  ): Future[SimulationInfo] = {
    SimulationState
      .getOrFail(observationRequest.simulationId.get)
      .databaseReaderState
      .stop

    SimulationState.restore(observationRequest.simulationId.get)
    val simulationState =
      SimulationState.getOrFail(observationRequest.simulationId.get)
    implicit val databaseReaderState = simulationState.databaseReaderState
    implicit val matchingEngineState = simulationState.matchingEngineState
    implicit val simulationMetadata = simulationState.simulationMetadata
    logger.info(
      s"Simulation ${simulationMetadata.simulationId} reset to ${simulationMetadata.currentTimeInterval}"
    )
    simulationMetadata.databaseReader.startPopulateQueryResultMapStream
    StepProgressLogger.reset

    Future successful getSimulationInfo(observationRequest)
  }

  override def run(runRequest: RunRequest): Future[SimulationInfo] = {
    val simulationMetadata = {
      SimulationState.getSimulationMetadataOrFail(runRequest.simulationId.get)
    }
    val numSteps = runRequest.numSteps
      .getOrElse(
        simulationMetadata.numSteps - simulationMetadata.currentStep
      )
      .toInt

    val stepRequest = StepRequest(
      actionRequest = runRequest.actionRequest,
      observationRequest = runRequest.observationRequest,
      simulationId = runRequest.simulationId
    )
    (1 to numSteps - 1).foreach { _ =>
      step(stepRequest)
    }

    step(stepRequest)
  }

  override def shutdown(request: Empty): Future[Empty] = {
    Future {
      Thread.sleep(1000)
      FakebaseServer.shutdown
    }
    Future successful Constants.emptyProto
  }

  override def start(
      simulationStartRequest: SimulationStartRequest
  ): Future[SimulationInfo] = {
    if (simulationStartRequest.stopInProgressSimulations)
      SimulationState.keys.foreach(stop(_))

    implicit val simulationMetadata =
      SimulationMetadata.fromSimulationStartRequest(simulationStartRequest)
    val simulationState = SimulationState.create(simulationMetadata)
    implicit val accountState = simulationState.accountState
    implicit val databaseReaderState = simulationState.databaseReaderState
    implicit val matchingEngineState = simulationState.matchingEngineState
    implicit val walletsState = accountState.walletsState

    logger.info(
      s"starting simulation ${simulationMetadata.simulationId} for parameters ${simulationMetadata}"
    )

    simulationMetadata.databaseReader.start(
      simulationStartRequest.skipDatabaseQuery
    )

    Account.initializeWallets
    Account.addFunds(simulationStartRequest.initialQuoteFunds)
    Account.addFunds(simulationStartRequest.initialProductFunds)
    MatchingEngine.start
    StepProgressLogger.reset

    if (simulationStartRequest.numWarmUpSteps > 0) {
      val stepRequest =
        StepRequest(
          actionRequest = simulationStartRequest.actionRequest,
          observationRequest = simulationStartRequest.observationRequest,
          simulationId = Some(simulationMetadata.simulationId)
        )
      (1 to simulationStartRequest.numWarmUpSteps) foreach (_ =>
        step(stepRequest)
      )

      if (!simulationStartRequest.skipCheckpointAfterWarmup) {
        checkpoint(simulationMetadata.simulationId)
      }
    }

    val observationRequest = simulationStartRequest.observationRequest.map {
      observationRequest =>
        observationRequest.update(
          _.simulationId := simulationMetadata.simulationId
        )
    }

    val simulationInfo = observationRequest
      .map(getSimulationInfo(_))
      .getOrElse(
        SimulationInfo(simulationId = Some(simulationMetadata.simulationId))
      )

    Future successful simulationInfo
  }

  override def step(stepRequest: StepRequest): Future[SimulationInfo] = {
    implicit val simulationState = SimulationState.getOrFail(
      stepRequest.simulationId.get
    )
    implicit val accountState = simulationState.accountState
    implicit val databaseReaderState = simulationState.databaseReaderState
    implicit val simulationMetadata = simulationState.simulationMetadata
    implicit val matchingEngineState = simulationState.matchingEngineState
    val stepStartTime = System.nanoTime
    simulationMetadata.incrementCurrentTimeInterval
    require(
      !simulationMetadata.simulationIsOver,
      "The simulation has ended. Please reset."
    )
    logger.fine(s"Stepped to ${simulationMetadata.currentTimeInterval}")

    Account.step
    Environment.preStep(
      stepRequest.actionRequest
        .map(_.update(_.simulationId := stepRequest.simulationId.get))
    )
    InfoAggregator.preStep

    val dataGetStartTime = System.nanoTime
    val queryResult = simulationMetadata.databaseReader
      .removeQueryResult(simulationMetadata.currentTimeInterval)

    simulationState.exchangeState.receivedEvents =
      (Account.getReceivedOrders.toList
        ++ Account.getReceivedCancellations.toList
        ++ stepRequest.insertOrders
          .map(OrderUtils.orderEventFromSealedOneOf)
          .flatten
        ++ stepRequest.insertCancellations
        ++ queryResult.events)
        .sortBy(event => event.time)

    val dataGetDuration = (System.nanoTime - dataGetStartTime) / 1e6

    if (simulationState.exchangeState.receivedEvents.isEmpty)
      logger.warning(
        s"No events queried for time interval ${simulationMetadata.currentTimeInterval}"
      )
    else
      logger.fine(
        s"Processing ${simulationState.exchangeState.receivedEvents.length} events"
      )

    simulationState.matchingEngineState.matches.clear

    val matchingEngineStartTime = System.nanoTime
    matchingEngine.step(simulationState.exchangeState.receivedEvents)
    val matchingEngineDuration =
      (System.nanoTime - matchingEngineStartTime) / 1e6

    val environmentStartTime = System.nanoTime
    Environment.step(simulationMetadata.observationRequest)
    val environmentDuration = (System.nanoTime - environmentStartTime) / 1e6

    val stepDuration = (System.nanoTime - stepStartTime) / 1e6
    InfoAggregator.step(
      stepDuration,
      dataGetDuration,
      matchingEngineDuration,
      environmentDuration,
      simulationState.exchangeState.receivedEvents.size,
      stepRequest.simulationId.get
    )

    val simulationInfo =
      stepRequest.observationRequest
        .map { observationRequest =>
          val _observationRequest = observationRequest.update(
            _.simulationId := simulationMetadata.simulationId
          )
          getSimulationInfo(_observationRequest)
        }
        .getOrElse(
          SimulationInfo(simulationId = Some(simulationMetadata.simulationId))
        )

    logger.fine(s"Exchange.step took ${stepDuration} ms")
    simulationMetadata.incrementStep
    StepProgressLogger.step(
      stepDuration,
      dataGetDuration,
      matchingEngineDuration,
      environmentDuration,
      simulationState.exchangeState.receivedEvents.size
    )

    Future successful simulationInfo
  }

  override def stop(simulationId: SimulationId): Future[Empty] = {
    if (SimulationState.contains(simulationId)) {
      SimulationState.remove(simulationId)
      logger.info(s"stopping ${simulationId}")
    } else {
      logger.info(s"${simulationId} not found")
    }
    Future.successful(Constants.emptyProto)
  }

  override def update(updateRequest: UpdateRequest): Future[Empty] = {
    val simulationId = updateRequest.simulationId.getOrElse(
      throw new IllegalArgumentException("simulationId is not defined")
    )

    val simulationState = SimulationState.getOrFail(simulationId)
    val environmentState = simulationState.environmentState
    implicit val updatedSimulationMetadata = simulationState.simulationMetadata
      .copy(
        actionizerConfigs =
          ActionizerConfigs fromSealedOneOf updateRequest.actionizerConfigs,
        actionizerInitialState = environmentState.actionizerState.some
      )

    val updatedSimulationState =
      simulationState.copy(
        simulationMetadata = updatedSimulationMetadata,
        environmentState = environmentState.copy(actionizerState =
          updatedSimulationMetadata.actionizer.actionizerState.create
        )
      )

    SimulationState.update(
      updatedSimulationMetadata.simulationId,
      updatedSimulationState
    )

    Future.successful(Constants.emptyProto)
  }

  def getDatabaseReader(
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

  private def getExchangeInfoHelper(
      simulationId: SimulationId
  ): ExchangeInfo = {
    SimulationState.get(simulationId) match {
      case Some(simulationState) => {
        val simulationMetadata = simulationState.simulationMetadata
        fakebase.ExchangeInfo(
          simulationMetadata.currentTimeInterval.startTime,
          simulationMetadata.currentTimeInterval.endTime,
          getResultOptional(Account.getAccountInfo(simulationId)),
          Some(simulationMetadata.simulationId)
        )
      }
      case None => ExchangeInfo()
    }
  }

  def getSimulationInfo(
      observationRequest: ObservationRequest
  ): SimulationInfo = {
    val exchangeInfo = getExchangeInfoHelper(
      observationRequest.simulationId.get
    )
    val observation = Some(
      getResult(
        Environment.getObservation(observationRequest)
      )
    )

    fakebase.SimulationInfo(
      Some(exchangeInfo),
      observation,
      observationRequest.simulationId
    )
  }
}

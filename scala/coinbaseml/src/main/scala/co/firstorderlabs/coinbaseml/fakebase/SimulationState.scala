package co.firstorderlabs.coinbaseml.fakebase

import java.time.{Duration, Instant}
import java.util.UUID.randomUUID

import co.firstorderlabs.coinbaseml.common.EnvironmentState
import co.firstorderlabs.coinbaseml.common.actions.actionizers.Actionizer
import co.firstorderlabs.coinbaseml.common.utils.ArrowUtils
import co.firstorderlabs.coinbaseml.fakebase.Types.Exceptions.SimulationNotStarted
import co.firstorderlabs.coinbaseml.fakebase.sql.{
  DatabaseReader,
  DatabaseReaderState
}
import co.firstorderlabs.common.currency.Configs.ProductPrice.{
  ProductVolume,
  QuoteVolume
}
import co.firstorderlabs.common.protos.environment.{
  Actionizer => ActionizerProto,
  InfoDict,
  ObservationRequest
}
import co.firstorderlabs.common.protos.fakebase.{
  SimulationStartRequest,
  SimulationType
}
import co.firstorderlabs.common.types.Types.{SimulationId, TimeInterval}

import scala.collection.mutable

trait State[A <: State[A]] {
  val companion: StateCompanion[A]

  def createSnapshot(implicit simulationState: SimulationState): A
}

trait StateCompanion[A <: State[A]] {
  def create(implicit simulationMetadata: SimulationMetadata): A

  def fromSnapshot(snapshot: A): A
}

final case class SimulationMetadata(
    startTime: Instant,
    endTime: Instant,
    timeDelta: Duration,
    numWarmUpSteps: Int,
    initialProductFunds: ProductVolume,
    initialQuoteFunds: QuoteVolume,
    simulationId: SimulationId,
    observationRequest: ObservationRequest,
    enableProgressBar: Boolean,
    simulationType: SimulationType,
    databaseReader: DatabaseReader,
    featureBufferSize: Int,
    backupToCloudStorage: Boolean,
    actionizerConfigs: Map[String, Double],
    actionizer: Actionizer,
    checkpointTimeInterval: Option[TimeInterval] = None,
    checkpointStep: Option[Long] = None
) {
  var currentTimeInterval = checkpointTimeInterval.getOrElse(
    TimeInterval(startTime.minus(timeDelta), startTime)
  )
  var currentStep = checkpointStep.getOrElse(0L)
  val taskName = s"${simulationType.name} simulation ${simulationId} progress"

  def createSnapshot: SimulationMetadata =
    SimulationMetadata(
      startTime,
      endTime,
      timeDelta,
      numWarmUpSteps,
      initialProductFunds,
      initialQuoteFunds,
      simulationId,
      observationRequest,
      enableProgressBar,
      simulationType,
      databaseReader,
      featureBufferSize,
      backupToCloudStorage,
      actionizerConfigs,
      actionizer,
      Some(currentTimeInterval),
      Some(currentStep)
    )

  def incrementCurrentTimeInterval: Unit =
    currentTimeInterval = currentTimeInterval + timeDelta

  def incrementStep: Unit = {
    currentStep += 1
  }

  def numSteps: Long =
    Duration.between(startTime, endTime).dividedBy(timeDelta)

  def previousTimeInterval: TimeInterval = {
    currentTimeInterval - timeDelta
  }

  def simulationIsOver: Boolean =
    currentTimeInterval.endTime isAfter endTime
}

object SimulationMetadata {
  def fromSimulationStartRequest(
      simulationStartRequest: SimulationStartRequest
  ): SimulationMetadata = {
    val simulationId = SimulationId(randomUUID.toString)
    val observationRequest = simulationStartRequest.observationRequest.get
      .update(_.simulationId := simulationId)
    SimulationMetadata(
      simulationStartRequest.startTime,
      simulationStartRequest.endTime,
      simulationStartRequest.timeDelta.get,
      simulationStartRequest.numWarmUpSteps,
      simulationStartRequest.initialProductFunds,
      simulationStartRequest.initialQuoteFunds,
      simulationId,
      observationRequest,
      simulationStartRequest.enableProgressBar,
      simulationStartRequest.simulationType,
      Exchange.getDatabaseReader(simulationStartRequest.databaseBackend),
      simulationStartRequest.snapshotBufferSize,
      simulationStartRequest.backupToCloudStorage,
      simulationStartRequest.actionizerConfigs,
      Actionizer.fromProto(
        simulationStartRequest.actionRequest
          .map(_.actionizer)
          .getOrElse(ActionizerProto.NoOpActionizer)
      )
    )
  }

  def fromSnapshot(snapshot: SimulationMetadata): SimulationMetadata =
    SimulationMetadata(
      snapshot.startTime,
      snapshot.endTime,
      snapshot.timeDelta,
      snapshot.numWarmUpSteps,
      snapshot.initialProductFunds,
      snapshot.initialQuoteFunds,
      snapshot.simulationId,
      snapshot.observationRequest,
      snapshot.enableProgressBar,
      snapshot.simulationType,
      snapshot.databaseReader,
      snapshot.featureBufferSize,
      snapshot.backupToCloudStorage,
      snapshot.actionizerConfigs,
      snapshot.actionizer,
      snapshot.checkpointTimeInterval,
      snapshot.checkpointStep
    )
}

final case class SimulationState(
    simulationMetadata: SimulationMetadata,
    accountState: AccountState,
    databaseReaderState: DatabaseReaderState,
    exchangeState: ExchangeState,
    environmentState: EnvironmentState,
    matchingEngineState: MatchingEngineState
) {
  def createSnapshot(implicit
      simulationState: SimulationState
  ): SimulationState =
    SimulationState(
      simulationMetadata.createSnapshot,
      accountState.createSnapshot,
      databaseReaderState.createSnapshot,
      exchangeState.createSnapshot,
      environmentState.createSnapshot,
      matchingEngineState.createSnapshot
    )
}

object SimulationState {
  private val simulationStates =
    new mutable.HashMap[SimulationId, SimulationState]
  private val simulationSnapshots =
    new mutable.HashMap[SimulationId, SimulationState]

  private def throwSimulationNotStartedException(
      simulationId: SimulationId
  ): Nothing =
    throw SimulationNotStarted(
      s"${simulationId} not found."
    )

  def contains(simulationId: SimulationId): Boolean =
    simulationStates.contains(simulationId)

  def create(implicit
      simulationMetadata: SimulationMetadata
  ): SimulationState = {
    val simulationState = SimulationState(
      simulationMetadata,
      AccountState.create,
      DatabaseReaderState.create,
      ExchangeState.create,
      EnvironmentState.create,
      MatchingEngineState.create
    )
    simulationStates.put(simulationMetadata.simulationId, simulationState)
    simulationState
  }

  def get(simulationId: SimulationId): Option[SimulationState] =
    simulationStates.get(simulationId)

  def keys: List[SimulationId] =
    simulationStates.keys.toList

  def remove(simulationId: SimulationId): Option[SimulationState] =
    simulationStates.get(simulationId) match {
      case Some(simulationState) => {
        simulationState.databaseReaderState.stop
        ArrowUtils.socketFile(simulationState.simulationMetadata).delete
        simulationStates.remove(simulationId)
        simulationSnapshots.remove(simulationId)
      }
      case None => None
    }

  def restore(simulationId: SimulationId): Option[SimulationState] =
    simulationSnapshots.get(simulationId) match {
      case Some(snapshot) => {
        val simulationState = SimulationState(
          SimulationMetadata.fromSnapshot(snapshot.simulationMetadata),
          AccountState.fromSnapshot(snapshot.accountState),
          DatabaseReaderState.fromSnapshot(snapshot.databaseReaderState),
          ExchangeState.fromSnapshot(snapshot.exchangeState),
          EnvironmentState.fromSnapshot(snapshot.environmentState),
          MatchingEngineState.fromSnapshot(snapshot.matchingEngineState)
        )
        simulationStates.put(simulationId, simulationState)
      }
      case None => None
    }

  def snapshot(
      simulationId: SimulationId
  )(implicit simulationState: SimulationState): Option[SimulationState] =
    simulationSnapshots.put(simulationId, simulationState.createSnapshot)

  def getSnapshot(simulationId: SimulationId): Option[SimulationState] =
    simulationSnapshots.get(simulationId)

  @throws[SimulationNotStarted]
  def getOrFail(simulationId: SimulationId): SimulationState =
    SimulationState.get(simulationId).getOrElse {
      throwSimulationNotStartedException(simulationId)
    }

  def getAccountStateOrFail(simulationId: SimulationId): AccountState =
    getOrFail(simulationId).accountState

  def getInfoDictOrFail(simulationId: SimulationId): InfoDict =
    getOrFail(simulationId).environmentState.infoAggregatorState.infoDict

  def getMatchingEngineStateOrFail(
      simulationId: SimulationId
  ): MatchingEngineState =
    getOrFail(simulationId).matchingEngineState

  def getSimulationMetadataOrFail(
      simulationId: SimulationId
  ): SimulationMetadata =
    getOrFail(simulationId).simulationMetadata

  def removeOrFail(simulationId: SimulationId): SimulationState = {
    remove(simulationId) match {
      case Some(simulationState) => simulationState
      case None                  => throwSimulationNotStartedException(simulationId)
    }
  }
}

package co.firstorderlabs.coinbaseml.fakebase

import co.firstorderlabs.coinbaseml.common.{Environment, FeaturizerSnapshot, InfoAggregator}
import co.firstorderlabs.coinbaseml.fakebase.sql.{DatabaseReader, DatabaseReaderSnapshot}
import co.firstorderlabs.coinbaseml.fakebase.types.Exceptions.CheckpointNotFound
import co.firstorderlabs.common.types.Types.TimeInterval

/** Provides functionality for creating snapshots of the simulation state in a given
  *  TimeInterval. This functionality is used by Checkpointer to create checkpoints of
  *  simulation state and restore simulation state to those checkpoints. Also is used
  *  by Featurizers to generate features that are functions of historic simulation state.
  */
/** All snapshots extend this trait
  */
trait Snapshot

/** All objects that can be snapshotted extend this trait
  *
  * @tparam A The class that will store snapshot data for the snapshotable objects.
  *           Must be child of Snapshot.
  */
trait Snapshotable[A <: Snapshot] {
  def createSnapshot: A
  def clear: Unit
  def isCleared: Boolean
  def restore(snapshot: A): Unit
}

/** Complete snapshot of the simulation for a given timeInterval. Basically a container
  *  class of the object specific snapshots.
  *
  * @param accountSnapshot
  * @param databaseWorkersSnapshot
  * @param exchangeSnapshot
  * @param matchingEngineSnapshot
  * @param featurizerSnapshot
  * @param timeInterval
  */
final case class SimulationSnapshot(accountSnapshot: AccountSnapshot,
                              databaseWorkersSnapshot: DatabaseReaderSnapshot,
                              exchangeSnapshot: ExchangeSnapshot,
                              matchingEngineSnapshot: MatchingEngineSnapshot,
                              featurizerSnapshot: FeaturizerSnapshot,
                              timeInterval: TimeInterval)
    extends Snapshot


object Checkpointer {
  var simulationSnapshot: Option[SimulationSnapshot] = None

  def checkpointSnapshot: SimulationSnapshot =
    simulationSnapshot match {
      case Some(simulationSnapshot) => simulationSnapshot
      case None                     => throw new IllegalStateException()
    }

  def checkpointExists: Boolean = simulationSnapshot.isDefined

  def checkpointTimeInterval: TimeInterval = {
    if (checkpointExists) {
      checkpointSnapshot.timeInterval
    } else {
      throw new CheckpointNotFound
    }
  }

  def createCheckpoint: Unit =
    simulationSnapshot = Some(createSnapshot)

  def createSnapshot: SimulationSnapshot = SimulationSnapshot(
      Account.createSnapshot,
      Exchange.getSimulationMetadata.databaseReader.createSnapshot,
      Exchange.createSnapshot,
      MatchingEngine.createSnapshot,
      Environment.createSnapshot,
      Exchange.getSimulationMetadata.currentTimeInterval
    )

  def clear: Unit = {
    Account.clear
    DatabaseReader.clearAllReaders
    Exchange.clear
    InfoAggregator.clear
    MatchingEngine.clear
    Environment.clear
  }

  def isCleared: Boolean =
    (Account.isCleared
    && DatabaseReader.areReadersCleared
    && Exchange.isCleared
    && InfoAggregator.isCleared
    && MatchingEngine.isCleared
    && Environment.isCleared)

  def restoreFromCheckpoint: Unit = {
    Account.restore(checkpointSnapshot.accountSnapshot)
    Exchange.getSimulationMetadata.databaseReader
      .restore(checkpointSnapshot.databaseWorkersSnapshot)
    Exchange.restore(checkpointSnapshot.exchangeSnapshot)
    MatchingEngine.restore(checkpointSnapshot.matchingEngineSnapshot)
    Environment.restore(checkpointSnapshot.featurizerSnapshot)
  }

  def start: Unit = simulationSnapshot = None
}

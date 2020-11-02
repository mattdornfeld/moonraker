package co.firstorderlabs.coinbaseml.fakebase

import co.firstorderlabs.coinbaseml.common.{Environment, InfoAggregator}
import co.firstorderlabs.coinbaseml.fakebase.sql.DatabaseReaderBase
import co.firstorderlabs.coinbaseml.fakebase.types.Exceptions.CheckpointNotFound
import co.firstorderlabs.coinbaseml.fakebase.types.Types.TimeInterval

object Checkpointer {
  private val checkpointSnapshotBuffer =
    new SnapshotBuffer(scala.Int.MaxValue)

  def checkpointSnapshot: SimulationSnapshot = checkpointSnapshotBuffer.last

  def checkpointExists: Boolean = checkpointSnapshotBuffer.nonEmpty

  def checkpointTimeInterval: TimeInterval = {
    if (checkpointExists) {
      checkpointSnapshot.timeInterval
    } else {
      throw new CheckpointNotFound
    }
  }

  def createCheckpoint: Unit = {
    checkpointSnapshotBuffer.clear
    SnapshotBuffer.snapshotBuffer
      .foreach(snapshot => checkpointSnapshotBuffer.enqueue(snapshot))
  }

  def clear: Unit = {
    Account.clear
    DatabaseReaderBase.clearAllReaders
    Exchange.clear
    InfoAggregator.clear
    MatchingEngine.clear
    SnapshotBuffer.clear
    Environment.clear
    checkpointSnapshotBuffer.clear
  }

  def isCleared: Boolean = {
    (Account.isCleared
    && DatabaseReaderBase.areReadersCleared
    && Exchange.isCleared
    && InfoAggregator.isCleared
    && MatchingEngine.isCleared
    && Environment.isCleared
    && SnapshotBuffer.isCleared)
  }

  def restoreFromCheckpoint: Unit = {
    SnapshotBuffer.restoreFromCheckpoint(checkpointSnapshotBuffer)
    Account.restore(checkpointSnapshot.accountSnapshot)
    Exchange.getSimulationMetadata.databaseReader.restore(checkpointSnapshot.databaseWorkersSnapshot)
    Exchange.restore(checkpointSnapshot.exchangeSnapshot)
    MatchingEngine.restore(checkpointSnapshot.matchingEngineSnapshot)
    Environment.restore(checkpointSnapshot.featurizerSnapshot)
  }

  def start(snapshotBufferSize: Int): Unit =
    checkpointSnapshotBuffer.setMaxSize(snapshotBufferSize)
}

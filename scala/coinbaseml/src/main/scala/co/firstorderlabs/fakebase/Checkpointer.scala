package co.firstorderlabs.fakebase

import co.firstorderlabs.common.InfoAggregator
import co.firstorderlabs.fakebase.types.Exceptions.CheckpointNotFound
import co.firstorderlabs.fakebase.types.Types.TimeInterval

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
    DatabaseWorkers.clear
    Exchange.clear
    InfoAggregator.clear
    MatchingEngine.clear
    SnapshotBuffer.clear
    checkpointSnapshotBuffer.clear
  }

  def isCleared: Boolean = {
    (Account.isCleared
    && DatabaseWorkers.isCleared
    && Exchange.isCleared
    && InfoAggregator.isCleared
    && MatchingEngine.isCleared
    && SnapshotBuffer.isCleared)
  }

  def restoreFromCheckpoint: Unit = {
    SnapshotBuffer.restoreFromCheckpoint(checkpointSnapshotBuffer)
    Account.restore(checkpointSnapshot.accountSnapshot)
    DatabaseWorkers.restore(checkpointSnapshot.databaseWorkersSnapshot)
    Exchange.restore(checkpointSnapshot.exchangeSnapshot)
    MatchingEngine.restore(checkpointSnapshot.matchingEngineSnapshot)
  }

  def start(snapshotBufferSize: Int): Unit =
    checkpointSnapshotBuffer.setMaxSize(snapshotBufferSize)
}

package co.firstorderlabs.coinbaseml.fakebase

import java.time.Duration

import co.firstorderlabs.coinbaseml.common.{Environment, FeaturizerSnapshot}
import co.firstorderlabs.coinbaseml.common.utils.BufferUtils.FiniteQueue
import co.firstorderlabs.coinbaseml.fakebase.sql.DatabaseReaderSnapshot
import co.firstorderlabs.coinbaseml.fakebase.types.Types.TimeInterval

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
case class SimulationSnapshot(accountSnapshot: AccountSnapshot,
                              databaseWorkersSnapshot: DatabaseReaderSnapshot,
                              exchangeSnapshot: ExchangeSnapshot,
                              matchingEngineSnapshot: MatchingEngineSnapshot,
                              featurizerSnapshot: FeaturizerSnapshot,
                              timeInterval: TimeInterval)
    extends Snapshot

class SnapshotBuffer(maxSize: Int) extends FiniteQueue[SimulationSnapshot](maxSize) {
  private var snapshotBufferArray: Option[Array[SimulationSnapshot]] = None

    /** Override addOne so that there are never more than maxSize elements in the queue
      *
      * @param elem
      * @return
      */
    override def addOne(elem: SimulationSnapshot): this.type = {
      super.addOne(elem)
      snapshotBufferArray = None
      this
    }

    /** Converts FiniteQueue to Array to support random access of elements. Caches the
      * result so that the array does not need to be created multiple times
      *
      * @return
      */
    def toArray: Array[SimulationSnapshot] = {
      if (snapshotBufferArray.isEmpty) {
        snapshotBufferArray = Some(super.toArray)
      }

      snapshotBufferArray.get
    }
}

/** SnapshotBuffer is a collection of methods for creating snapshots of simulation state,
  *  storing them in a SnapshotBuffer class, iterating over the SnapshotBuffer, and restoring
  *  state from a checkpoint.
  *
  */
object SnapshotBuffer {
  val snapshotBuffer =
    new SnapshotBuffer(scala.Int.MaxValue)

  def clear: Unit = snapshotBuffer.clear

  def createSnapshot: SimulationSnapshot = SimulationSnapshot(
    Account.createSnapshot,
    Exchange.getSimulationMetadata.databaseReader.createSnapshot,
    Exchange.createSnapshot,
    MatchingEngine.createSnapshot,
    Environment.createSnapshot,
    Exchange.getSimulationMetadata.currentTimeInterval
  )

  private def isIndexOutOfBounds(snapshotBufferIndex: Int): Boolean =
    snapshotBufferIndex < 0 || snapshotBufferIndex >= size || size == 0

  def maxSize: Int = snapshotBuffer.getMaxSize

  def mostRecentSnapshot: SimulationSnapshot =
    getSnapshot(Exchange.getSimulationMetadata.currentTimeInterval)

  def getSnapshot(timeInterval: TimeInterval): SimulationSnapshot = {
    val i = getSnapshotBufferIndex(timeInterval)
    if (isIndexOutOfBounds(i)) {
      throw new ArrayIndexOutOfBoundsException(
        s"TimeInterval ${timeInterval} had index ${i}. SnapshotBuffer only has ${size} elements."
      )
    }

    toArray.apply(i)
  }

  def getSnapshotOrElse(timeInterval: TimeInterval, default: Option[SimulationSnapshot] = None): Option[SimulationSnapshot] = {
    val i = getSnapshotBufferIndex(timeInterval)
    if (isIndexOutOfBounds(i)) {
      default
    } else {
      Some(toArray.apply(i))
    }
  }

  private def getSnapshotBufferIndex(timeInterval: TimeInterval): Int = {
    val simulationMetaData = Exchange.getSimulationMetadata

    val numStepsInPast = Duration
      .between(
        simulationMetaData.currentTimeInterval.startTime,
        timeInterval.startTime
      )
      .dividedBy(simulationMetaData.timeDelta)
      .toInt
      .abs

    size - numStepsInPast - 1
  }

  def isCleared: Boolean = snapshotBuffer.isEmpty

  def toList: List[SimulationSnapshot] = snapshotBuffer.toList

  def restoreFromCheckpoint(snapshotBufferCheckpoint: SnapshotBuffer): Unit = {
    clear
    snapshotBufferCheckpoint
      .foreach(snapshot => snapshotBuffer.enqueue(snapshot))
  }

  def size: Int = snapshotBuffer.size

  def start(snapshotBufferSize: Int): Unit = {
    snapshotBuffer.setMaxSize(snapshotBufferSize)
    Checkpointer.start(snapshotBufferSize)
    Environment.start(snapshotBufferSize)
  }

  def step: Unit = snapshotBuffer.enqueue(createSnapshot)

  def toArray: Array[SimulationSnapshot] = snapshotBuffer.toArray
}

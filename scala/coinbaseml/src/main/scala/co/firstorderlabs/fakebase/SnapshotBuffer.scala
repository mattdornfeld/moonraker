package co.firstorderlabs.fakebase

import java.time.Duration

import co.firstorderlabs.fakebase.types.Types.TimeInterval

import scala.collection.mutable

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
  * @param timeInterval
  */
case class SimulationSnapshot(accountSnapshot: AccountSnapshot,
                              databaseWorkersSnapshot: DatabaseWorkersSnapshot,
                              exchangeSnapshot: ExchangeSnapshot,
                              matchingEngineSnapshot: MatchingEngineSnapshot,
                              timeInterval: TimeInterval)
    extends Snapshot

/** SnapshotBuffer is a mutable queue class with a maximum size used to store instances of
  *  SimulationSnapshot. As new snapshots are enqueued old ones are removed so that there
  *  are never more than maxSize elements in the queue. The last element of the queue is the
  *  most recent snapshot. The first element is the oldest.

  * @param maxSize
  */
class SnapshotBuffer(maxSize: Int) extends mutable.Queue[SimulationSnapshot] {
  private var _maxSize = maxSize
  private var snapshotBufferArray: Option[Array[SimulationSnapshot]] = None

  /** Override addOne so that there are never more than maxSize elements in the queue
    *
    * @param simulationSnapshot
    * @return
    */
  override def addOne(simulationSnapshot: SimulationSnapshot): this.type = {
    if (length >= _maxSize) dequeue()
    super.addOne(simulationSnapshot)
    snapshotBufferArray = None
    this
  }

  /** Get max size of the queue
    *
    * @return
    */
  def getMaxSize: Int = _maxSize

  /** Set max size of the queue
    *
    * @param maxSize
    */
  def setMaxSize(maxSize: Int): Unit = _maxSize = maxSize

  /** Converts SnapshotBuffer to Array to support random access of elements. Caches the
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
    DatabaseWorkers.createSnapshot,
    Exchange.createSnapshot,
    MatchingEngine.createSnapshot,
    Exchange.getSimulationMetadata.currentTimeInterval
  )

  def maxSize: Int = snapshotBuffer.getMaxSize

  def mostRecentSnapshot: SimulationSnapshot =
    getSnapshot(Exchange.getSimulationMetadata.currentTimeInterval)

  def getSnapshot(timeInterval: TimeInterval): SimulationSnapshot = {
    val simulationMetaData = Exchange.getSimulationMetadata

    val numStepsInPast = Duration
      .between(
        simulationMetaData.currentTimeInterval.startTime,
        timeInterval.startTime
      )
      .dividedBy(simulationMetaData.timeDelta)
      .toInt
      .abs

    val i = size - numStepsInPast - 1
    if (i < 0 || size == 0) {
      throw new ArrayIndexOutOfBoundsException(
        s"TimeInterval ${timeInterval} is ${numStepsInPast} time steps in the past. " +
          s"SnapshotBuffer only has ${size} elements."
      )
    }

    toArray.apply(i)
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
  }

  def step: Unit = snapshotBuffer.enqueue(createSnapshot)

  def toArray: Array[SimulationSnapshot] = snapshotBuffer.toArray
}

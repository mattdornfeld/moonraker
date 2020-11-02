package co.firstorderlabs.coinbaseml.fakebase

import java.time.Duration

import co.firstorderlabs.coinbaseml.fakebase.TestData.RequestsData._
import org.scalatest.funspec.AnyFunSpec

class SnapshotBufferTest extends AnyFunSpec {
  describe("SnapshotBuffer") {
    Configs.testMode = true

    it(
      "The SnapshotBuffer maxSize should be equal to the max size specified in the start request." +
        "It should also be clear on start"
    ) {
      Exchange.start(simulationStartRequest)
      assert(
        SnapshotBuffer.maxSize == simulationStartRequest.snapshotBufferSize
      )
      assert(SnapshotBuffer.isCleared)
    }

    it("The SnapshotBuffer should never have more than maxSize elements") {
      Exchange.start(simulationStartRequest)
      (1 to 3) foreach { i =>
        Exchange.step(Constants.emptyStepRequest)
        assert(SnapshotBuffer.snapshotBuffer.length == i)
      }
      Exchange.step(Constants.emptyStepRequest)
    }

    it(
      "The last element of the SnapshotBuffer should be the most recent snapshot"
    ) {
      Exchange.start(simulationStartRequest)
      (1 to 3) foreach { _ =>
        Exchange.step(Constants.emptyStepRequest)
        assert(
          SnapshotBuffer.snapshotBuffer.last == SnapshotBuffer.createSnapshot
        )
      }
    }

    it("The SnapshotBuffer should be sorted by time in ascending order") {
      Exchange.start(simulationStartRequest)
      (1 to 3) foreach { _ =>
        Exchange.step(Constants.emptyStepRequest)
      }
      val snapshotBufferSeq = SnapshotBuffer.snapshotBuffer.toSeq
      assert(
        snapshotBufferSeq == snapshotBufferSeq
          .sortBy(snapshot => snapshot.timeInterval.startTime)
      )
    }

    it(
      "The duration between consecutive snapshots in the SnapshotBuffer should be equal to the simulation timeDelta"
    ) {
      Exchange.start(simulationStartRequest)
      (1 to 3) foreach { _ =>
        Exchange.step(Constants.emptyStepRequest)
      }
      val startTimes = SnapshotBuffer.snapshotBuffer
        .map(snapshot => snapshot.timeInterval.startTime)
        .toSeq
      val durations = for ((t1, t2) <- startTimes zip startTimes.drop(1))
        yield Duration.between(t1, t2)
      assert(
        durations.forall(
          duration =>
            duration.compareTo(Exchange.getSimulationMetadata.timeDelta) == 0
        )
      )
    }
  }
}

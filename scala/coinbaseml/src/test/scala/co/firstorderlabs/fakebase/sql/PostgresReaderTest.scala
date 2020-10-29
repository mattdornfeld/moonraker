package co.firstorderlabs.fakebase.sql

import co.firstorderlabs.fakebase.sql.TestData._
import co.firstorderlabs.fakebase.sql.{Configs => SqlConfigs}
import co.firstorderlabs.fakebase.{Configs, Exchange}
import org.scalatest.funspec.AnyFunSpec

class PostgresReaderTest extends AnyFunSpec {
  Configs.testMode = true

  describe("PostgresReader") {
    it(
      "When PostgresReader.start is called, timeIntervalQueue should be populated by " +
        "sub-intervals of (startTime. endTime). The reader threads will then pop off elements" +
        "of that queue. For this test the queryResultMap.maxSize is set to 0, which will freeze" +
        "the reader threads in this state."
    ) {
      PostgresReader.setQueryResultMapMaxSize(0)
      PostgresReader.start(startTime, endTime, timeDelta)
      PostgresReader.waitUntilPaused

      assert(
        expectedTimeIntervals
          .drop(SqlConfigs.numDatabaseWorkers)
          .iterator sameElements PostgresReader.timeIntervalQueueElements
      )
      assert(PostgresReader.isPaused)
      PostgresReader.setQueryResultMapMaxSize(SqlConfigs.maxResultsQueueSize)
    }

    it(
      "PostgresReader Threads should pop off elements from timeIntervalQueue and add the query results for thos elements" +
        "to queryResultMap. This should be done until timeIntervalQueue is empty. queryResultMap should be keyed by the elements" +
        "from timeIntervalQueue."
    ) {
      PostgresReader.start(startTime, endTime, timeDelta)
      PostgresReader.waitUntilPaused

      assert(PostgresReader.timeIntervalQueueSize == 0)
      assert(expectedTimeIntervals.toSet == PostgresReader.queryResultMapKeys)
      assert(expectedTimeIntervals.size == PostgresReader.getResultMapSize)
    }

    it(
      "When PostgresReader.getQueryResult is called the QueryResult for passed in TimeInterval" +
        "should be removed and returned. The size of the queryResultMap should decrease by 1."
    ) {
      PostgresReader.start(startTime, endTime, timeDelta)
      PostgresReader.waitUntilPaused
      val expectedQueryResult = QueryResult(
        List(),
        List(),
        List(),
        List(),
        List(),
        expectedTimeIntervals(0)
      )

      assert(
        expectedQueryResult == PostgresReader
          .getQueryResult(expectedTimeIntervals(0))
      )
      assert(expectedTimeIntervals.size - 1 == PostgresReader.getResultMapSize)
    }

    it(
      "PostgresReader should snapshot and restore the timeIntervalQueue correctly."
    ) {
      PostgresReader.setQueryResultMapMaxSize(5)
      Exchange.start(simulationStartRequest)
      PostgresReader.waitUntilPaused
      val snapshot = PostgresReader.createSnapshot
      // The snapshot should contain the elements of expectedTimeIntervals minus the ones that are included in the
      // warmup period. Note that Exchange prepends one TimeInterval to the passed in to Exchange.start so even
      // though the warmupPeriod = 3 we only drop the first 2 elements of expectedTimeIntervals
      assert(
        expectedTimeIntervals
          .drop(2)
          .iterator sameElements snapshot.timeIntervalQueue.toArray
      )

      // After creating the snapshot we test to make sure PostgresReader gets restored to the correct state when
      // PostgresReader.restore(snapshot) is called. First we make sure PostgresReader.queryResultMap is empty.
      // Then we call restore. From there we should expect each reader thread to pop off an element of
      // PostgresReader.timeIntervalQueue. So we drop those elements from expectedTimeIntervalQueue before comparing
      // it to the restored queue.
      PostgresReader.clear
      PostgresReader.setQueryResultMapMaxSize(0)
      PostgresReader.restore(snapshot)
      PostgresReader.waitUntilPaused
      assert(
        snapshot.timeIntervalQueue.toArray.drop(
          SqlConfigs.numDatabaseWorkers
        ) sameElements PostgresReader.timeIntervalQueueElements
      )
      PostgresReader.setQueryResultMapMaxSize(SqlConfigs.maxResultsQueueSize)
    }
  }
}

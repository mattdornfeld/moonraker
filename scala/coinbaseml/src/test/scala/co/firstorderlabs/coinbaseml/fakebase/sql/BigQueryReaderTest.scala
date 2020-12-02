package co.firstorderlabs.coinbaseml.fakebase.sql

import java.time.Instant

import co.firstorderlabs.coinbaseml.common.utils.TestUtils.{FutureUtils, waitUntil}
import co.firstorderlabs.coinbaseml.fakebase.Constants.emptyStepRequest
import co.firstorderlabs.coinbaseml.fakebase.sql.{Configs => SQLConfigs}
import co.firstorderlabs.coinbaseml.fakebase.sql.TestData._
import co.firstorderlabs.coinbaseml.fakebase.{Configs, Exchange}
import co.firstorderlabs.common.types.Types.TimeInterval
import com.simba.googlebigquery.googlebigquery.BigQuery
import org.scalatest.funspec.AnyFunSpec

import scala.concurrent.duration.DurationInt

class BigQueryReaderTest extends AnyFunSpec {
  Configs.testMode = true

  describe("BigQueryReader") {
    ignore(
      "This integration test is not automatically run. If your local has access to BigQuery you can un-ignore it and run" +
        "it manually to test BigQueryReader queries results correctly."
    ) {
      Configs.testMode = false
      BigQueryReader.start(startTime, endTime, timeDelta)
      val expectedNumKeys =
        TimeInterval(startTime, endTime).chunkBy(timeDelta).size
      waitUntil(() => BigQueryReader.queryResultMapKeys.size == expectedNumKeys)
      val keys = BigQueryReader.queryResultMapKeys
      val printSize = (queryResult: QueryResult) => {
        println(s"TimeInterval: ${queryResult.timeInterval}")
        println(s"\tnumEvents: ${queryResult.events.size}\n")
      }
      keys.foreach(k => printSize(BigQueryReader.removeQueryResult(k)))
      Configs.testMode = true
    }

    it(
      "BigQueryReader should add the sub TimeIntervals of the passed in interval as keys to LocalStorage." +
        "It should then cache the earliest QueryResults in memory in queryResultMap."
    ) {
      LocalStorage.clear
      BigQueryReader.start(startTime, endTime, timeDelta)
      assert(expectedTimeIntervals.toSet == LocalStorage.keys.toSet)
      BigQueryReader.streamFuture.get.await(1000.milliseconds)
      println(expectedTimeIntervals.size)
      println(BigQueryReader.queryResultMapKeys.size)
      assert(
        expectedTimeIntervals.toSet == BigQueryReader.queryResultMapKeys.toSet
      )
    }

    it(
      "Ensure the BigQuery JDBC driver can be imported since it's only accessed by reflection at runtime by Doobie"
    ) {
      val bigQuery = new BigQuery
    }

    it(
      "When getQueryResult is called the queryResult for the passed in TimeInterval should be returned. This should remove" +
        "the key from the in memory cache and have no effect on LocalStorage."
    ) {
      LocalStorage.clear
      BigQueryReader.start(startTime, endTime, timeDelta)
      BigQueryReader.streamFuture.get.await(1000.milliseconds)
      val queryResult = BigQueryReader.removeQueryResult(expectedTimeIntervals(0))
      assert(expectedTimeIntervals(0) == queryResult.timeInterval)
      assert(
        !BigQueryReader.queryResultMapKeys.toSet
          .contains(queryResult.timeInterval)
      )
      assert(expectedTimeIntervals.toSet == LocalStorage.keys.toSet)
    }

    it("When clear is called the state should be cleared") {
      BigQueryReader.start(startTime, endTime, timeDelta)
      BigQueryReader.streamFuture.get.await(1000.milliseconds)
      BigQueryReader.clear
      assert(BigQueryReader.isCleared)
      assert(BigQueryReader.queryResultMapKeys.size == 0)
    }

    it("The contents of queryResultMap should be properly restored when restore is called.") {
      Exchange.start(simulationStartRequest)
      BigQueryReader.streamFuture.get.await(1000.milliseconds)
      val expectedKeys = BigQueryReader.queryResultMapKeys
      val expectedSnapshot = BigQueryReader.createSnapshot
      Exchange.step(emptyStepRequest)
      BigQueryReader.restore(expectedSnapshot)
      BigQueryReader.streamFuture.get.await(1000.milliseconds)
      assert(expectedKeys.toSet == BigQueryReader.queryResultMapKeys.toSet)
    }

    it("The queryResultMap should fill up and the streamFuture should block until space in it becomes available." +
      "When it does the next element should be pulled from LocalStorage and added to the map.") {
      val startTime = Instant.parse("2020-11-09T00:00:00.0Z")
      val endTime = Instant.parse("2020-11-09T02:00:00.0Z")
      BigQueryReader.start(startTime, endTime, timeDelta)
      Thread.sleep(1000)
      assert(SQLConfigs.maxResultsQueueSize == BigQueryReader.queryResultMapKeys.size)
      assert(!BigQueryReader.streamFuture.get.isCompleted)
      val timeInterval = BigQueryReader.queryResultMapKeys.toList.head
      BigQueryReader.removeQueryResult(timeInterval)
      Thread.sleep(100)
      assert(SQLConfigs.maxResultsQueueSize == BigQueryReader.queryResultMapKeys.size)
    }
  }
}

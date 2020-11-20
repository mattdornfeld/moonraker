package co.firstorderlabs.coinbaseml.fakebase.sql

import co.firstorderlabs.coinbaseml.common.utils.TestUtils.waitUntil
import co.firstorderlabs.coinbaseml.fakebase.Configs
import co.firstorderlabs.coinbaseml.fakebase.sql.TestData._
import co.firstorderlabs.common.types.Types.TimeInterval
import com.simba.googlebigquery.googlebigquery.BigQuery
import org.scalatest.funspec.AnyFunSpec

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class BigQueryReaderTest extends AnyFunSpec {
  Configs.testMode = true

  describe("BigQueryReader") {
    ignore("This integration test is not automatically run. If your local has access to BigQuery you can un-ignore it and run" +
      "it manually to test BigQueryReader queries results correctly.") {
      Configs.testMode = false
      BigQueryReader.start(startTime, endTime, timeDelta)
      val expectedNumKeys = TimeInterval(startTime, endTime).chunkBy(timeDelta).size
      waitUntil(() => BigQueryReader.queryResultMapKeys.size == expectedNumKeys)
      val keys = BigQueryReader.queryResultMapKeys
      val printSize = (queryResult: QueryResult) => {
        println(s"TimeInterval: ${queryResult.timeInterval}")
        println(s"\tnumEvents: ${queryResult.events.size}\n")
      }
      keys.foreach(k => printSize(BigQueryReader.getQueryResult(k)))
      Configs.testMode = true
    }

    it("Ensure the BigQuery JDBC driver can be imported since it's only accessed by reflection at runtime by Doobie") {
      val bigQuery = new BigQuery
    }

    it("BigQueryReader should add the sub TimeIntervals of the passed in interval as keys to SwayDbStorage." +
      "It should then cache the earliest QueryResults in memory in queryResultMap.") {
      val dataTransfer = BigQueryReader.start(startTime, endTime, timeDelta)
      Await.ready(dataTransfer, Duration.Inf)
      assert(expectedTimeIntervals.toSet == SwayDbStorage.keys.toSet)
      BigQueryReader.blockUntil(Thread.State.TIMED_WAITING)
      assert(expectedTimeIntervals.toSet == BigQueryReader.queryResultMapKeys.toSet)
    }

    it("When getQueryResult is called the queryResult for the passed in TimeInterval should be returned. This should remove" +
      "the key from the in memory cache and have no effect on SwayDbStorage.") {
      BigQueryReader.start(startTime, endTime, timeDelta)
      BigQueryReader.blockUntil(Thread.State.TIMED_WAITING)
      val queryResult = BigQueryReader.getQueryResult(expectedTimeIntervals(0))
      assert(expectedTimeIntervals(0) == queryResult.timeInterval)
      assert(!BigQueryReader.queryResultMapKeys.toSet.contains(queryResult.timeInterval))
      assert(expectedTimeIntervals.toSet == SwayDbStorage.keys.toSet)
    }

    it("When clear is called the state should be cleared") {
      BigQueryReader.start(startTime, endTime, timeDelta)
      BigQueryReader.blockUntil(Thread.State.TIMED_WAITING)
      BigQueryReader.clear
      assert(BigQueryReader.isCleared)
      assert(BigQueryReader.queryResultMapKeys.size == 0)
    }
  }

}

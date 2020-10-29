package co.firstorderlabs.fakebase.sql

import co.firstorderlabs.fakebase.Configs
import co.firstorderlabs.fakebase.sql.TestData._
import org.scalatest.funspec.AnyFunSpec

class BigQueryReaderTest extends AnyFunSpec {
  Configs.testMode = true

  describe("BigQueryReader") {
    it("BigQueryReader should add the sub TimeIntervals of the passed in interval as keys to queryResultMap") {
      BigQueryReader.start(startTime, endTime, timeDelta)
      assert(expectedTimeIntervals.toSet == BigQueryReader.queryResultMapKeys)
    }

    it("When getQueryResult the queryResult for the passed in TimeInterval should be returned. This should have" +
      "no effect on queryResultMap.") {
      BigQueryReader.start(startTime, endTime, timeDelta)
      val queryResult = BigQueryReader.getQueryResult(expectedTimeIntervals(0))
      assert(expectedTimeIntervals(0) == queryResult.timeInterval)
      assert(expectedTimeIntervals.toSet == BigQueryReader.queryResultMapKeys)
    }

    it("When clear is called the state should be cleared") {
      BigQueryReader.start(startTime, endTime, timeDelta)
      BigQueryReader.clear
      assert(BigQueryReader.isCleared)
    }
  }

}

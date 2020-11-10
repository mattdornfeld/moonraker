package co.firstorderlabs.coinbaseml.fakebase.sql

import co.firstorderlabs.coinbaseml.fakebase.Configs
import co.firstorderlabs.coinbaseml.fakebase.sql.TestData._
import com.simba.googlebigquery.googlebigquery.BigQuery
import org.scalatest.funspec.AnyFunSpec

class BigQueryReaderTest extends AnyFunSpec {
  Configs.testMode = true

  describe("BigQueryReader") {
    it("Ensure the BigQuery JDBC driver can be imported since it's only accessed by reflection at runtime by Doobie") {
      val bigQuery = new BigQuery
    }

    ignore("This integration test is not automatically run. If your local has access to BigQuery you can un-ignore it and run" +
      "it manually to test BigQueryReader queries results correctly.") {
      Configs.testMode = false
      BigQueryReader.start(startTime, endTime, timeDelta)
      val keys = BigQueryReader.queryResultMapKeys
      val printSize = (queryResult: QueryResult) => {
        println(s"TimeInterval: ${queryResult.timeInterval}")
        println(s"\tnumCancellations: ${queryResult.cancellations.size}")
        println(s"\tnumBuyLimitOrders: ${queryResult.buyLimitOrders.size}")
        println(s"\tnumBuyMarketOrders: ${queryResult.buyMarketOrders.size}")
        println(s"\tnumSellLimitOrders: ${queryResult.sellLimitOrders.size}")
        println(s"\tnumSellMarketOrders: ${queryResult.sellMarketOrder.size}\n")
      }
      keys.foreach(k => printSize(BigQueryReader.getQueryResult(k)))
      Configs.testMode = true
    }

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

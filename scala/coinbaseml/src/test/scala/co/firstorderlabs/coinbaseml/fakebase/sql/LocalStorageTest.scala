package co.firstorderlabs.coinbaseml.fakebase.sql

import java.time.Instant

import co.firstorderlabs.coinbaseml.fakebase.{Configs, Exchange}
import co.firstorderlabs.coinbaseml.fakebase.TestData.OrdersData.{higherOrder, lowerOrder}
import co.firstorderlabs.coinbaseml.fakebase.sql.{Configs => SqlConfigs}
import co.firstorderlabs.coinbaseml.fakebase.sql.TestData.{endTime, simulationStartRequest, startTime, timeDelta}
import co.firstorderlabs.common.currency.Price.BtcUsdPrice.productId
import co.firstorderlabs.common.types.Types.TimeInterval
import org.scalatest.funspec.AnyFunSpec

class LocalStorageTest extends AnyFunSpec {
  Configs.testMode = true
  Exchange.start(simulationStartRequest)
  val notPresentKey = TimeInterval(Instant.MIN, Instant.MAX)
  val presentKey = TimeInterval(startTime, endTime)
  val queryResult = QueryResult(List(lowerOrder, higherOrder), presentKey)
  SqlConfigs.queryResultMapMaxOverflow = 0
  describe("LocalStorage") {
    ignore("The keys in LocalStorage should correspond to the time interval specified in simulationStartRequest.") {
      LocalStorage.clear
      Exchange.start(simulationStartRequest)
      val expectedTimeIntervals = TimeInterval(
        simulationStartRequest.startTime,
        simulationStartRequest.endTime
      ).chunkBy(simulationStartRequest.timeDelta.get)
      assert(LocalStorage.QueryResults.keys.size == expectedTimeIntervals.size)
    }

    ignore("When recordQuerySuccess is called, containsDataForQuery should return true for that key") {
      LocalStorage.clear
      Exchange.start(simulationStartRequest)
      val timeInterval = TimeInterval(Instant.MIN, Instant.MIN.plus(timeDelta))
      val queryHistoryKey = QueryHistoryKey(productId, timeInterval, timeDelta)
      assert(!LocalStorage.QueryHistory.contains(queryHistoryKey))
      LocalStorage.QueryHistory.put(queryHistoryKey)
      assert(LocalStorage.QueryHistory.contains(queryHistoryKey))
    }

    ignore(
      "A (TimeInterval, QueryResult) key-value pair should be added to LocalStorage with the put method and retrieved with " +
        "the get method."
    ) {
      LocalStorage.clear
      Exchange.start(simulationStartRequest)
      LocalStorage.QueryResults.put(presentKey, queryResult)
      assert(LocalStorage.QueryResults.get(presentKey).get == queryResult)
    }

    ignore("If a key is not present in LocalStorage get should return empty.") {
      LocalStorage.clear
      Exchange.start(simulationStartRequest)
      assert(LocalStorage.QueryResults.get(notPresentKey).isEmpty)
    }
  }
}

package co.firstorderlabs.coinbaseml.fakebase.sql

import java.time.Instant

import co.firstorderlabs.coinbaseml.fakebase.{Configs, Exchange}
import co.firstorderlabs.coinbaseml.fakebase.TestData.OrdersData.{
  higherOrder,
  lowerOrder
}
import co.firstorderlabs.coinbaseml.fakebase.sql.{Configs => SqlConfigs}
import co.firstorderlabs.coinbaseml.fakebase.sql.TestData.{
  endTime,
  simulationStartRequest,
  startTime,
  timeDelta
}
import co.firstorderlabs.common.currency.Price.BtcUsdPrice.productId
import co.firstorderlabs.common.types.Types.TimeInterval
import org.scalatest.funspec.AnyFunSpec

class LocalStorageTest extends AnyFunSpec {
  Configs.testMode = true
  Exchange.start(simulationStartRequest)
  val notPresentKey = TimeInterval(Instant.MIN, Instant.MAX)
  val presentKey = TimeInterval(startTime, endTime)
  val queryHistoryKey = QueryHistoryKey(productId, presentKey, timeDelta)
  val queryResult = QueryResult(List(lowerOrder, higherOrder), presentKey)
  SqlConfigs.queryResultMapMaxOverflow = 0

  def createSstFileWriter: QueryResultSstFileWriter = {
    val sstFileWriter = QueryResultSstFileWriter(queryHistoryKey)
    sstFileWriter.put(presentKey, queryResult)
    sstFileWriter.finish
    sstFileWriter
  }

  describe("CloudStorage") {
    it("If no file is uploaded to a key CloudStorage.contains should return false.") {
      CloudStorage.clear
      assert(!CloudStorage.contains(queryHistoryKey))
    }

    it(
      "When an sst file is uploaded to CloudStorage the contain method should return true for that key"
    ) {
      CloudStorage.clear
      val sstFileWriter = createSstFileWriter
      CloudStorage.put(queryHistoryKey, sstFileWriter.sstFile)
      assert(CloudStorage.contains(queryHistoryKey))
    }

    it(
      "When an sst file is uploaded to CloudStorage it should be retrievable and ingestable by LocalStorage"
    ) {
      CloudStorage.clear
      LocalStorage.clear
      val sstFileWriter = createSstFileWriter
      CloudStorage.put(queryHistoryKey, sstFileWriter.sstFile)
      sstFileWriter.sstFile.delete
      CloudStorage.get(queryHistoryKey)
      LocalStorage.QueryResults.bulkIngest(List(sstFileWriter))
      assert(LocalStorage.QueryResults.get(presentKey).get == queryResult)
      assert(LocalStorage.QueryHistory.contains(queryHistoryKey))
    }
  }

  describe("LocalStorage") {
    it(
      "The keys in LocalStorage should correspond to the time interval specified in simulationStartRequest."
    ) {
      CloudStorage.clear
      LocalStorage.clear
      Exchange.start(simulationStartRequest)
      val expectedTimeIntervals = TimeInterval(
        simulationStartRequest.startTime,
        simulationStartRequest.endTime
      ).chunkBy(simulationStartRequest.timeDelta.get)
      assert(LocalStorage.QueryResults.keys.size == expectedTimeIntervals.size)
    }

    it(
      "When QueryHistory.put is called, QueryHistory.contains should return true for that key"
    ) {
      CloudStorage.clear
      LocalStorage.clear
      Exchange.start(simulationStartRequest)
      val timeInterval = TimeInterval(Instant.MIN, Instant.MIN.plus(timeDelta))
      val queryHistoryKey = QueryHistoryKey(productId, timeInterval, timeDelta)
      assert(!LocalStorage.QueryHistory.contains(queryHistoryKey))
      LocalStorage.QueryHistory.put(queryHistoryKey)
      assert(LocalStorage.QueryHistory.contains(queryHistoryKey))
    }

    it(
      "A (TimeInterval, QueryResult) key-value pair should be added to LocalStorage with the put method and retrieved with " +
        "the get method."
    ) {
      CloudStorage.clear
      LocalStorage.clear
      Exchange.start(simulationStartRequest)
      LocalStorage.QueryResults.put(presentKey, queryResult)
      assert(LocalStorage.QueryResults.get(presentKey).get == queryResult)
    }

    it("If a key is not present in LocalStorage get should return empty.") {
      CloudStorage.clear
      LocalStorage.clear
      Exchange.start(simulationStartRequest)
      assert(LocalStorage.QueryResults.get(notPresentKey).isEmpty)
    }

    it(
      "When an sst file is ingested the contents should be available in the QueryResults column family. The query should" +
        "be recorded in QueryHistory."
    ) {
      LocalStorage.clear
      val sstFileWriter = createSstFileWriter
      LocalStorage.QueryResults.bulkIngest(List(sstFileWriter))
      assert(LocalStorage.QueryResults.get(presentKey).get == queryResult)
      assert(LocalStorage.QueryHistory.contains(queryHistoryKey))
    }
  }
}

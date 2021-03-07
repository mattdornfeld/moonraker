package co.firstorderlabs.coinbaseml.fakebase.sql

import java.time.Instant

import co.firstorderlabs.coinbaseml.common.utils.TestUtils.{buildStepRequest, waitUntil}
import co.firstorderlabs.coinbaseml.common.utils.Utils.{FutureUtils, getResult}
import co.firstorderlabs.coinbaseml.fakebase.sql.TestData._
import co.firstorderlabs.coinbaseml.fakebase.sql.{Configs => SQLConfigs}
import co.firstorderlabs.coinbaseml.fakebase.{Configs, Exchange, SimulationMetadata, SimulationState}
import co.firstorderlabs.common.currency.Price.BtcUsdPrice.{ProductVolume, QuoteVolume}
import co.firstorderlabs.common.protos.environment.ObservationRequest
import co.firstorderlabs.common.protos.fakebase.SimulationStartRequest
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
      implicit val databaseReaderState = DatabaseReaderState.create
      BigQueryReader.start()
      val expectedNumKeys =
        TimeInterval(startTime, endTime).chunkBy(timeDelta).size
      waitUntil(() =>
        databaseReaderState.queryResultMapKeys.size == expectedNumKeys
      )
      val keys = databaseReaderState.queryResultMapKeys
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
      implicit val databaseReaderState = DatabaseReaderState.create
      BigQueryReader.start()
      assert(
        expectedTimeIntervals.toSet == LocalStorage.QueryResults.keys.toSet
      )
      databaseReaderState.streamFuture.get.await(1000.milliseconds)
      assert(
        expectedTimeIntervals.toSet == databaseReaderState.queryResultMapKeys.toSet
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
      implicit val databaseReaderState = DatabaseReaderState.create
      BigQueryReader.start()
      databaseReaderState.streamFuture.get.await(1000.milliseconds)
      val queryResult =
        BigQueryReader.removeQueryResult(expectedTimeIntervals(0))
      assert(expectedTimeIntervals(0) == queryResult.timeInterval)
      assert(
        !databaseReaderState.queryResultMapKeys.toSet
          .contains(queryResult.timeInterval)
      )
      assert(
        expectedTimeIntervals.toSet == LocalStorage.QueryResults.keys.toSet
      )
    }

    it(
      "The contents of queryResultMap should be properly restored when restore is called."
    ) {
      val simulationId =
        getResult(Exchange.start(simulationStartRequest)).simulationId.get
      implicit val simulationState = SimulationState.getOrFail(simulationId)
      implicit val databaseReaderState = simulationState.databaseReaderState
      databaseReaderState.streamFuture.get.await(1000.milliseconds)
      val expectedKeys = databaseReaderState.queryResultMapKeys
      val expectedSnapshot = databaseReaderState.createSnapshot
      Exchange.step(buildStepRequest(simulationId))
      SimulationState.restore(simulationId)
      databaseReaderState.streamFuture.get.await(1000.milliseconds)
      assert(expectedKeys.toSet == databaseReaderState.queryResultMapKeys.toSet)
    }

    it(
      "The queryResultMap should fill up and the streamFuture should block until space in it becomes available." +
        "When it does the next element should be pulled from LocalStorage and added to the map."
    ) {
      val simulationStartRequest = new SimulationStartRequest(
        Instant.parse("2020-11-09T00:00:00.0Z"),
        Instant.parse("2020-11-09T02:00:00.0Z"),
        Some(timeDelta),
        3,
        new ProductVolume(Right("100.000000")),
        new QuoteVolume(Right("10000.00")),
        snapshotBufferSize = 3,
        observationRequest = Some(new ObservationRequest(10))
      )
      implicit val simulationMetadata =
        SimulationMetadata.fromSimulationStartRequest(simulationStartRequest)
      implicit val databaseReaderState = DatabaseReaderState.create
      BigQueryReader.start()
      Thread.sleep(1000)
      assert(
        SQLConfigs.maxQueryResultMapSize == databaseReaderState.queryResultMapKeys.size
      )
      assert(!databaseReaderState.streamFuture.get.isCompleted)
      val timeInterval = databaseReaderState.queryResultMapKeys.toList.head
      BigQueryReader.removeQueryResult(timeInterval)
      Thread.sleep(100)
      assert(
        SQLConfigs.maxQueryResultMapSize == databaseReaderState.queryResultMapKeys.size
      )
    }
  }
}

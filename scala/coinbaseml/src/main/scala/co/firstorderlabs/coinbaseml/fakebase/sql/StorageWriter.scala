package co.firstorderlabs.coinbaseml.fakebase.sql

import java.time.Duration

import co.firstorderlabs.coinbaseml.fakebase.Exchange
import co.firstorderlabs.common.protos.fakebase.{DatabaseBackend, PopulateStorageParameters, PopulateStorageRequest}
import co.firstorderlabs.common.types.Types.TimeInterval

/**
  * Mini app for populating LocalStorage and CloudStorage with data stored in DatabaseBackend
  */
object StorageWriter {

  /**
    * Configs for app. Modify these as necessary.
    */
  object Configs {
    val timeIntervals: Seq[TimeInterval] = Seq(TimeInterval.fromStrings("2020-11-19T00:00:00.00Z", "2020-11-20T00:00:00.00Z"))
    val timeDeltas: Seq[Duration] = Seq(Duration.ofSeconds(30))
    val databaseBackend = DatabaseBackend.BigQuery
    val backupToCloudStorage = true
    val ingestToLocalStorage = false
  }

  def main(args: Array[String]): Unit = {
    val populateStorageParameters =
      (Configs.timeIntervals, Configs.timeDeltas).zipped.toList.map { item =>
        PopulateStorageParameters(item._1.startTime, item._1.endTime, Some(item._2))
      }
    val populateStorageRequest = PopulateStorageRequest(
      populateStorageParameters = populateStorageParameters,
      ingestToLocalStorage = Configs.ingestToLocalStorage,
      backupToCloudStorage = Configs.backupToCloudStorage,
      databaseBackend = Configs.databaseBackend

    )
    Exchange.populateStorage(populateStorageRequest)
  }
}

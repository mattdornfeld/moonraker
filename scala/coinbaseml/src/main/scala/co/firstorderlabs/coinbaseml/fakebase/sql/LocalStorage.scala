package co.firstorderlabs.coinbaseml.fakebase.sql

import java.io._
import java.nio.file.Files
import java.time.Duration
import java.util
import java.util.logging.Logger

import co.firstorderlabs.coinbaseml.common.utils.Utils.When
import co.firstorderlabs.coinbaseml.fakebase.Configs
import co.firstorderlabs.coinbaseml.fakebase.sql.Implicits.Serializer
import co.firstorderlabs.coinbaseml.fakebase.sql.{Configs => SQLConfigs}
import co.firstorderlabs.common.types.Types.{ProductId, TimeInterval}
import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.storage.{BlobId, BlobInfo, StorageOptions}
import org.rocksdb.{
  ColumnFamilyDescriptor,
  ColumnFamilyHandle,
  ColumnFamilyOptions,
  DBOptions,
  EnvOptions,
  IngestExternalFileOptions,
  Options,
  RocksDB,
  SstFileWriter
}

import scala.jdk.CollectionConverters.SeqHasAsJava

case class QueryHistoryKey(
    productId: ProductId,
    timeInterval: TimeInterval,
    timeDelta: Duration
) {
  override def toString: String = s"${productId}-${timeInterval}-${timeDelta}"

  def getBlobId: BlobId =
    BlobId.of(
      SQLConfigs.sstBackupGcsBucket,
      s"${SQLConfigs.sstBackupBasePath}/${toString}.sst"
    )

  def getSstFile: File = {
    val file = new File(getSstFilePath)
    file.getParentFile.mkdirs
    file.createNewFile
    file
  }

  def getSstFilePath: String =
    s"${SQLConfigs.sstFilesPath}/${toString}.sst"
}

object QueryHistoryKey extends Deserializer[QueryHistoryKey]

/**
  * Used to upload and retrieve backups of SST files created by LocalStorage. This is useful so the database backend
  * only needs to be queried once, which decreases costs for backends like BigQuery.
  */
object CloudStorage {
  private val gcsStorage = StorageOptions.newBuilder
    .when(!Configs.testMode)(
      _.setCredentials(
        GoogleCredentials.fromStream(
          new FileInputStream(SQLConfigs.serviceAccountJsonPath)
        )
      )
    )
    .setProjectId(SQLConfigs.gcpProjectId)
    .build
    .getService

  /** Contains (timeInterval, timeDelta) key
    *
    * @param queryHistoryKey
    * @return
    */
  def contains(queryHistoryKey: QueryHistoryKey): Boolean =
    gcsStorage.get(queryHistoryKey.getBlobId) != null

  def get(queryHistoryKey: QueryHistoryKey): File = {
    val sstFile = queryHistoryKey.getSstFile
    val fileOutputStream = new FileOutputStream(sstFile)
    gcsStorage.get(queryHistoryKey.getBlobId).downloadTo(fileOutputStream)
    fileOutputStream.close
    sstFile
  }

  /** Uploads sstFiles to
    *
    * @param queryHistoryKey timeInterval and timeDelta for which the query was executed
    * @param sstFile sstFile containing the results of the query
    */
  def put(
      queryHistoryKey: QueryHistoryKey,
      sstFile: File
  ): Unit = {
    val blobInfo =
      BlobInfo.newBuilder(queryHistoryKey.getBlobId).build
    gcsStorage.create(blobInfo, Files.readAllBytes(sstFile.toPath))
  }
}

/**
  * Used to store data obtained from the database backend locally
  */
object LocalStorage {
  RocksDB.loadLibrary()

  private val logger = Logger.getLogger(toString)
  private val ingestExternalFileOptions =
    (new IngestExternalFileOptions).setMoveFiles(true)

  val columnFamilyOptions =
    new ColumnFamilyOptions().optimizeUniversalStyleCompaction()
  val columnFamilyDescriptors = new util.ArrayList[ColumnFamilyDescriptor]()
  columnFamilyDescriptors.add(
    new ColumnFamilyDescriptor(
      RocksDB.DEFAULT_COLUMN_FAMILY,
      columnFamilyOptions
    )
  )
  columnFamilyDescriptors.add(
    new ColumnFamilyDescriptor(
      "queryResults".getBytes(),
      columnFamilyOptions
    )
  )
  columnFamilyDescriptors.add(
    new ColumnFamilyDescriptor("queryHistory".getBytes(), columnFamilyOptions)
  )
  val columnFamilyHandles = new util.ArrayList[ColumnFamilyHandle]()

  val dBOptions = new DBOptions()
    .setCreateIfMissing(true)
    .setCreateMissingColumnFamilies(true)

  val rocksDbDir = if (Configs.testMode || Configs.localMode) {
    val dir = Files.createTempDirectory("coinbaseml_local_storage_").toFile
    dir.deleteOnExit
    dir.getAbsolutePath
  } else {
    SQLConfigs.localStoragePath
  }

  private var database =
    RocksDB.open(
      dBOptions,
      rocksDbDir,
      columnFamilyDescriptors,
      columnFamilyHandles
    )
  private var queryResults = columnFamilyHandles.get(1)
  private var queryHistory = columnFamilyHandles.get(2)

  /**
    * Switch RocksDb to write mode
    */
  def activateWriteMode = {
    database.closeE
    database = RocksDB.open(
      dBOptions,
      rocksDbDir,
      columnFamilyDescriptors,
      columnFamilyHandles
    )
    queryResults = columnFamilyHandles.get(1)
    queryHistory = columnFamilyHandles.get(2)
  }

  /**
    * Compact keys of RocksDb. Should be done after ingestion.
    */
  def compact: Unit = {
    logger.info("Compacting LocalStorage.")
    database.compactRange(queryResults)
    database.compactRange(queryHistory)
    logger.info("Compaction finished.")
  }

  /**
    * Delete all elements of RocksDb
    */
  def clear: Unit = {
    QueryResults.keys.foreach(timeInterval => {
      database.delete(queryResults, timeInterval.serialize)
    })
    QueryHistory.keys.foreach(queryHistoryKey =>
      database.delete(queryHistory, queryHistoryKey.serialize)
    )
  }

  /**
    * Switch RocksDb to read mode
    * @param shouldCompact
    */
  def deactivateWriteMode(shouldCompact: Boolean = true) = {
    if (shouldCompact) compact
    database.closeE
    database = RocksDB.openReadOnly(
      dBOptions,
      rocksDbDir,
      columnFamilyDescriptors,
      columnFamilyHandles
    )
    queryResults = columnFamilyHandles.get(1)
    queryHistory = columnFamilyHandles.get(2)
  }

  object QueryResults {

    /**
      * Add (key, value) pairs of iter to RocksDb
      *
      * @param iter
      */
    def addAll(iter: Iterator[(TimeInterval, QueryResult)]): Unit =
      iter.foreach(i => put(i._1, i._2))

    /** Bulk ingest the contents of a list of sstFiles
      *
      * @param sstFiles
      */
    def bulkIngest(sstFiles: Seq[File]): Unit =
      database.ingestExternalFile(
        queryResults,
        sstFiles.map(_.getAbsolutePath).asJava,
        ingestExternalFileOptions
      )

    /**
      * Get value from timeInterval Key
      * @param timeInterval
      * @return
      */
    def get(timeInterval: TimeInterval): Option[QueryResult] = {
      val bytes = database.get(queryResults, timeInterval.serialize)
      if (bytes == null) {
        None
      } else {
        Some(QueryResult.deserialize(bytes))
      }
    }

    /**
      * List keys in column family
      * @return
      */
    def keys: Iterator[TimeInterval] =
      new Iterator[TimeInterval] {
        private val rocksIterator = database.newIterator(queryResults)
        rocksIterator.seekToFirst
        override def hasNext: Boolean =
          rocksIterator.isValid

        override def next(): TimeInterval = {
          val timeInterval = TimeInterval.deserialize(rocksIterator.key)
          rocksIterator.next
          timeInterval
        }
      }

    /**
      * Add (key, value) pair to RocksDb
      * @param timeInterval
      * @param queryResult
      * @return
      */
    def put(
        timeInterval: TimeInterval,
        queryResult: QueryResult
    ): Option[QueryResult] = {
      database.put(
        queryResults,
        timeInterval.serialize,
        queryResult.serialize
      )
      Some(queryResult)
    }
  }

  object QueryHistory {
    private val emptyByteArray = new Array[Byte](0)

    /**
      * Add Iterator[QueryHistoryKey] to column family
      *
      * @param iter
      */
    def addAll(iter: Iterator[QueryHistoryKey]): Unit =
      iter.foreach(queryHistoryKey => put(queryHistoryKey))

    /**
      * Record a query success. This is used so that the database backend doesn't need to be queried for data already
      * in LocalStorage.
      * @param queryHistoryKey
      */
    def put(queryHistoryKey: QueryHistoryKey): Unit =
      database.put(queryHistory, queryHistoryKey.serialize, emptyByteArray)

    /**
      * Check if LocalStorage contains data for queryHistoryKey
      * @param queryHistoryKey
      * @return
      */
    def contains(queryHistoryKey: QueryHistoryKey): Boolean =
      database.get(
        queryHistory,
        queryHistoryKey.serialize
      ) != null

    /**
      * List queries already written to LocalStorage
      * @return
      */
    def keys: Iterator[QueryHistoryKey] =
      new Iterator[QueryHistoryKey] {
        private val rocksIterator = database.newIterator(queryHistory)
        rocksIterator.seekToFirst

        override def hasNext: Boolean =
          rocksIterator.isValid

        override def next(): QueryHistoryKey = {
          val timeInterval = QueryHistoryKey.deserialize(rocksIterator.key)
          rocksIterator.next
          timeInterval
        }
      }
  }
}

class QueryResultSstFileWriter(queryHistoryKey: QueryHistoryKey)
    extends SstFileWriter(
      (new EnvOptions).setUseDirectWrites(true),
      new Options(LocalStorage.dBOptions, LocalStorage.columnFamilyOptions)
    ) {
  val sstFile = queryHistoryKey.getSstFile
  open(sstFile.getAbsolutePath)

  def addAll(iterator: Iterator[(TimeInterval, QueryResult)]): Unit = {
    iterator.foreach(item => put(item._1.serialize, item._2.serialize))
  }

  def put(key: TimeInterval, value: QueryResult): Unit =
    super.put(key.serialize, value.serialize)
}

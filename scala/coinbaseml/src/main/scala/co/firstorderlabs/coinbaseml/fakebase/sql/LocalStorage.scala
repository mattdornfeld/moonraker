package co.firstorderlabs.coinbaseml.fakebase.sql

import java.io.{ByteArrayOutputStream, ObjectOutputStream}
import java.nio.file.Files
import java.time.Duration
import java.util
import java.util.logging.Logger

import co.firstorderlabs.coinbaseml.fakebase.Configs
import co.firstorderlabs.coinbaseml.fakebase.sql.{Configs => SQLConfigs}
import co.firstorderlabs.common.types.Types.TimeInterval
import org.rocksdb.{ColumnFamilyDescriptor, ColumnFamilyHandle, ColumnFamilyOptions, DBOptions, RocksDB}

object LocalStorage {
  protected val logger = Logger.getLogger(toString)

  private implicit class Serializer(obj: Object) {
    def serialize: Array[Byte] = {
      val stream = new ByteArrayOutputStream()
      val objectOutputStream = new ObjectOutputStream(stream)
      objectOutputStream.writeObject(obj)
      objectOutputStream.close
      stream.toByteArray
    }
  }

  case class QueryHistoryKey(
      timeInterval: TimeInterval,
      timeDelta: Duration
  )

  object QueryHistoryKey extends Deserializer[QueryHistoryKey]

  RocksDB.loadLibrary()
  val columnFamilyOptions = new ColumnFamilyOptions().optimizeUniversalStyleCompaction()
  val columnFamilyDescriptors = new util.ArrayList[ColumnFamilyDescriptor]()
  columnFamilyDescriptors.add(
    new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, columnFamilyOptions)
  )
  columnFamilyDescriptors.add(
    new ColumnFamilyDescriptor("queryResultsMap".getBytes(), columnFamilyOptions)
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
    RocksDB.open(dBOptions, rocksDbDir, columnFamilyDescriptors, columnFamilyHandles)
  private var queryResultsMap = columnFamilyHandles.get(1)
  private var queryHistory = columnFamilyHandles.get(2)
  private val emptyByteArray = new Array[Byte](0)

  def compact: Unit = {
    logger.info("Compacting LocalStorage.")
    database.compactRange(queryResultsMap)
    database.compactRange(queryHistory)
    logger.info("Compaction finished.")
  }

  def activateWriteMode = {
    database.closeE
    database = RocksDB.open(dBOptions, rocksDbDir, columnFamilyDescriptors, columnFamilyHandles)
    queryResultsMap = columnFamilyHandles.get(1)
    queryHistory = columnFamilyHandles.get(2)
  }

  def deactivateWriteMode(shouldCompact: Boolean = true) = {
    if (shouldCompact) compact
    database.closeE
    database = RocksDB.openReadOnly(dBOptions, rocksDbDir, columnFamilyDescriptors, columnFamilyHandles)
    queryResultsMap = columnFamilyHandles.get(1)
    queryHistory = columnFamilyHandles.get(2)
  }

  def addAll(iter: Iterator[(TimeInterval, QueryResult)]): Unit =
    iter.foreach(i => put(i._1, i._2))

  def clear: Unit = {
    keys.foreach(timeInterval => {database.delete(queryResultsMap, timeInterval.serialize)})
    queryHistoryKeys.foreach(queryHistoryKey => database.delete(queryHistory, queryHistoryKey.serialize))
  }

  def put(
      timeInterval: TimeInterval,
      queryResult: QueryResult
  ): Option[QueryResult] = {
    database.put(queryResultsMap, timeInterval.serialize, queryResult.serialize)
    Some(queryResult)
  }

  def get(timeInterval: TimeInterval): Option[QueryResult] = {
    val bytes = database.get(queryResultsMap, timeInterval.serialize)
    if (bytes == null) {
      None
    } else {
      Some(QueryResult.deserialize(bytes))
    }
  }

  def keys: Iterator[TimeInterval] =
    new Iterator[TimeInterval] {
      private val rocksIterator = database.newIterator(queryResultsMap)
      rocksIterator.seekToFirst
      override def hasNext: Boolean =
        rocksIterator.isValid

      override def next(): TimeInterval = {
        val timeInterval = TimeInterval.deserialize(rocksIterator.key)
        rocksIterator.next
        timeInterval
      }
    }

  def recordQuerySuccess(
      timeInterval: TimeInterval,
      timeDelta: Duration
  ): Unit =
    database.put(
      queryHistory,
      QueryHistoryKey(timeInterval, timeDelta).serialize,
      emptyByteArray
    )

  def containsDataForQuery(
      timeInterval: TimeInterval,
      timeDelta: Duration
  ): Boolean =
    database.get(
      queryHistory,
      QueryHistoryKey(timeInterval, timeDelta).serialize
    ) != null

  def queryHistoryKeys: Iterator[QueryHistoryKey] =
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

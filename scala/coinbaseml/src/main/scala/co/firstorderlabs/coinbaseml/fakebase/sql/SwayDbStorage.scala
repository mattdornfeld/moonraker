package co.firstorderlabs.coinbaseml.fakebase.sql

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, ObjectInputStream, ObjectOutputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.{Duration, Instant}
import java.util.Base64

import co.firstorderlabs.coinbaseml.fakebase.Configs
import co.firstorderlabs.coinbaseml.fakebase.sql.{Configs => SQLConfigs}
import co.firstorderlabs.common.types.Events.Event
import co.firstorderlabs.common.types.Types.TimeInterval
import swaydb.Bag.glass
import swaydb.data.slice.Slice
import swaydb.serializers.Default.StringSerializer
import swaydb.serializers.Serializer
import swaydb.{Glass, OK, memory, persistent}

object SwayDbStorage {
  private val intNumBytes = 32
  private val longNumbBytes = 64
  private val timeIntervalNumBytes = 2 * longNumbBytes

  case class SwayDbKey(timeInterval: TimeInterval, timeDelta: Duration)

  case class EventsString(eventsString: String) extends AnyVal {
    def deserialize: List[Event] = {
      val bytes = Base64.getDecoder().decode(eventsString.getBytes(UTF_8))
      val objectInputStream = new ObjectInputStream(new ByteArrayInputStream(bytes))
      val value = objectInputStream.readObject.asInstanceOf[List[Event]]
      objectInputStream.close
      value
    }
  }

  private implicit class EventsSerializer(events: List[Event]) {
    def serialize: String = {
      val stream = new ByteArrayOutputStream()
      val objectOutputStream = new ObjectOutputStream(stream)
      objectOutputStream.writeObject(events)
      objectOutputStream.close
      new String(Base64.getEncoder().encode(stream.toByteArray), UTF_8)
    }
  }

  private implicit val swayDbKeySerializer =
    new Serializer[SwayDbKey] {
      override def write(swayDbKey: SwayDbKey): Slice[Byte] =
        Slice
          .ofBytesScala(timeIntervalNumBytes + longNumbBytes)
          .addLong(swayDbKey.timeInterval.startTime.toEpochMilli)
          .addLong(swayDbKey.timeInterval.endTime.toEpochMilli)
          .addLong(swayDbKey.timeDelta.toNanos)
          .close()

      override def read(slice: Slice[Byte]): SwayDbKey = {
        val reader = slice.createReader()
        val timeInterval = TimeInterval(
          Instant.ofEpochMilli(reader.readLong),
          Instant.ofEpochMilli(reader.readLong)
        )
        val timeDelta = Duration.ofNanos(reader.readLong)
        SwayDbKey(timeInterval, timeDelta)
      }
    }

  private implicit val queryResultSerializer =
    new Serializer[QueryResult] {
      override def write(queryResult: QueryResult): Slice[Byte] = {
        val events = queryResult.events.serialize
        val eventsNumBytes = events.getBytes(UTF_8).size
        Slice
          .ofBytesScala(timeIntervalNumBytes + intNumBytes + eventsNumBytes)
          .addLong(queryResult.timeInterval.startTime.toEpochMilli)
          .addLong(queryResult.timeInterval.endTime.toEpochMilli)
          .addInt(eventsNumBytes)
          .addString(queryResult.events.serialize)
          .close()
      }

      override def read(slice: Slice[Byte]): QueryResult = {
          val reader = slice.createReader()
          val timeInterval = TimeInterval(
            Instant.ofEpochMilli(reader.readLong),
            Instant.ofEpochMilli(reader.readLong)
          )
          val eventsNumBytes = reader.readInt
          val events = EventsString(reader.readString(eventsNumBytes)).deserialize
          QueryResult(events, timeInterval)
      }
    }

  private val database =
    if (Configs.testMode)
      memory.MultiMap[String, SwayDbKey, QueryResult, Nothing, Glass](SQLConfigs.swayDbMapSize)
    else
      persistent.MultiMap[String, SwayDbKey, QueryResult, Nothing, Glass](
        SQLConfigs.swayDbPath,
        SQLConfigs.swayDbMapSize,
        SQLConfigs.swayDbMapSize
      )

  private val _queryHistory = database.child("queryHistory")
  private val _queryResultMap = database.child("queryResultMap")
  private val queryResultMap = _queryResultMap.asScala
  private val queryHistory = _queryHistory.asScala

  private def constructKey(timeInterval: TimeInterval, timeDelta: Option[Duration] = None): SwayDbKey =
    SwayDbKey(timeInterval, timeDelta.getOrElse(timeInterval.size))

  def addAll(iter: Iterator[(TimeInterval, QueryResult)]): Glass[OK] =
    _queryResultMap.put(iter.map(i => (constructKey(i._1), i._2)))

  def put(timeInterval: TimeInterval, queryResult: QueryResult): Option[QueryResult] =
    queryResultMap.put(constructKey(timeInterval), queryResult)

  def get(timeInterval: TimeInterval): Option[QueryResult] =
    queryResultMap.get(constructKey(timeInterval))

  def keys: Iterator[TimeInterval] =
    _queryResultMap.iterator.map(_._1.timeInterval)

  def recordQuerySuccess(timeInterval: TimeInterval, timeDelta: Duration): Unit =
    queryHistory.put(constructKey(timeInterval, Some(timeDelta)), QueryResult(List(), timeInterval))

  def containsDataForQuery(timeInterval: TimeInterval, timeDelta: Duration): Boolean =
    queryHistory.contains(constructKey(timeInterval, Some(timeDelta)))
}

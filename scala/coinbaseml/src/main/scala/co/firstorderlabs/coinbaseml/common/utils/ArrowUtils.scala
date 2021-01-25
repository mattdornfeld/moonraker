package co.firstorderlabs.coinbaseml.common.utils

import java.io.{File, FileInputStream, FileOutputStream}
import java.nio.channels.Channels

import co.firstorderlabs.coinbaseml.fakebase.SimulationMetadata
import co.firstorderlabs.common.types.Types.Features
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.ipc.message.IpcOption
import org.apache.arrow.vector.ipc.{ArrowStreamReader, ArrowStreamWriter}
import org.apache.arrow.vector.{FieldVector, Float8Vector, VectorSchemaRoot}

import scala.jdk.CollectionConverters._

/**
  * Utilities for sending data to other processes with Apache Arrow
  */
object ArrowUtils {
  private val allocator = new RootAllocator()
  private val socketDir = new File("/tmp/moonraker/coinbaseml/arrow_sockets")
  socketDir.mkdirs
  socketDir.deleteOnExit

  def socketFile(implicit
      simulationMetadata: SimulationMetadata
  ): File = {
    val file = new File(
      socketDir,
      s"${simulationMetadata.simulationId.simulationId}.socket"
    )
    file.deleteOnExit
    file
  }

  def readVectorFromSocket(implicit
      simulationMetadata: SimulationMetadata
  ): List[List[Double]] = {
    val reader = new SocketReader(socketFile)
    reader.hasNext
    val vectors = reader.next.map { vector =>
      val listDouble = (0 to vector.getValueCount - 1).toList.map { i =>
        val value = vector.getObject(i)
        if (value != null) Some(value.toDouble) else None
      }.flatten
      vector.close
      listDouble
    }
    reader.close
    vectors
  }

  implicit class ArrowFeatures(features: Features) {
    private def getVectorSchemaRoot(
        vectors: List[FieldVector]
    ): VectorSchemaRoot =
      new VectorSchemaRoot(vectors.asJava)

    private def toArrowVectors: List[Float8Vector] = {
      val maxVectorLength =
        features.features.values.map(_.size).maxOption.getOrElse(0)
      features.features.map { item =>
        val key = item._1
        val feature = item._2
        val vector = new Float8Vector(key, allocator)
        vector.allocateNew(maxVectorLength)
        feature.zipWithIndex.foreach(item => vector.setSafe(item._2, item._1))
        vector.setValueCount(maxVectorLength)
        vector
      }.toList
    }

    def writeToSocket(implicit simulationMetadata: SimulationMetadata): Unit = {
      val vectors = toArrowVectors
      val vectorSchemaRoot = getVectorSchemaRoot(vectors)
      val channel = Channels.newChannel(new FileOutputStream(socketFile))
      val ipcOption = new IpcOption
      ipcOption.write_legacy_ipc_format = true
      val writer =
        new ArrowStreamWriter(vectorSchemaRoot, null, channel, ipcOption)
      writer.start
      writer.writeBatch
      writer.close
      channel.close
      vectors.foreach(_.close)
    }
  }

  /** Iterates over Float8Vector in a Apache Arrow socket file
    *
    * @param socketFile
    */
  class SocketReader(socketFile: File) extends Iterator[List[Float8Vector]] {
    private val reader =
      new ArrowStreamReader(new FileInputStream(socketFile), allocator)

    def close: Unit =
      reader.close

    def hasNext: Boolean = {
      if (reader.loadNextBatch) {
        true
      } else {
        close
        false
      }
    }
    def next: List[Float8Vector] =
      reader.getVectorSchemaRoot.getFieldVectors.asScala.toList
        .map(_.asInstanceOf[Float8Vector])
  }
}

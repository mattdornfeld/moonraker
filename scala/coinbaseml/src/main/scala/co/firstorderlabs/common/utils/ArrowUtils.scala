package co.firstorderlabs.common.utils

import java.io.{File, FileInputStream, FileOutputStream}
import java.nio.channels.Channels

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

  /** Enhances a Seq[Double] with functionality to transmit its contents with Apache Arrow
    *
    * @param seq
    */
  implicit class ArrowSeq(seq: Seq[Double]) {
    private def getVectorSchemaRoot(vector: FieldVector): VectorSchemaRoot = {
      val vectors = List(vector).asJava
      new VectorSchemaRoot(vectors)
    }

    private def toArrowVector: Float8Vector = {
      val vector = new Float8Vector("vector", allocator)
      vector.allocateNew(seq.size)
      seq.zipWithIndex.foreach(item => vector.set(item._2, item._1))
      vector.setValueCount(seq.size)

      vector
    }

    /** Writes the contents of seq to a socket file
      *
      * @param socketFile
      */
    def writeToSocket(socketFile: File): Unit = {
      val vector = toArrowVector
      val vectorSchemaRoot = getVectorSchemaRoot(vector)
      val channel = Channels.newChannel(new FileOutputStream(socketFile))
      val ipcOption = (new IpcOption)
      ipcOption.write_legacy_ipc_format = true
      val writer =
        new ArrowStreamWriter(vectorSchemaRoot, null, channel, ipcOption)
      writer.start
      writer.writeBatch
      writer.close
      vector.close
    }
  }

  /** Iterates over Float8Vector in a Apache Arrow socket file
    *
    * @param socketFile
    */
  class SocketReader(socketFile: File) extends Iterator[Float8Vector] {
    private val reader =
      new ArrowStreamReader(new FileInputStream(socketFile), allocator)

    def close: Unit = reader.close

    def hasNext: Boolean = {
      if (reader.loadNextBatch) {
        true
      } else {
        close
        false
      }
    }
    def next: Float8Vector =
      reader.getVectorSchemaRoot.getVector(0).asInstanceOf[Float8Vector]
  }
}

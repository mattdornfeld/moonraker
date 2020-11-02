package co.firstorderlabs.coinbaseml.common.types

import java.io.File

import co.firstorderlabs.coinbaseml.common.protos.Features
import co.firstorderlabs.coinbaseml.common.utils.ArrowUtils
import co.firstorderlabs.coinbaseml.common.utils.ArrowUtils.ArrowSeq
import co.firstorderlabs.coinbaseml.fakebase.Exchange

trait FeaturesBase {
  val account: Seq[Double]
  val orderBook: Seq[Double]
  val timeSeries: Seq[Double]

  def writeToSockets: Unit = {
    if (!FeaturesBase.socketFileDir.exists) {
      FeaturesBase.socketFileDir.mkdirs
    }

    Seq(
      (account, "account"),
      (orderBook, "orderBook"),
      (timeSeries, "timeSeries")
    ).foreach(item =>
      item._1
        .writeToSocket(
          new File(FeaturesBase.socketFileDir, s"${item._2}.socket")
        )
    )
  }
}

object FeaturesBase {
  private val socketDir = "/tmp/coinbaseml/arrow_sockets"

  private def readVectorFromSocket(socketFile: File): List[Double] = {
    val reader = new ArrowUtils.SocketReader(socketFile)
    reader.hasNext
    val vector = reader.next
    val listDouble = for (i <- (0 to vector.getValueCount - 1).toList)
      yield vector.getValueAsDouble(i)
    reader.close
    vector.close
    listDouble
  }

  def fromArrowSockets(): Features = {
    new Features(
      readVectorFromSocket(new File(socketFileDir, "account.socket")),
      readVectorFromSocket(new File(socketFileDir, "orderBook.socket")),
      readVectorFromSocket(new File(socketFileDir, "timeSeries.socket"))
    )

  }

  def socketFileDir: File = {
    val file = new File(
      s"${socketDir}/${Exchange.getSimulationMetadata.simulationId}"
    )
    file.deleteOnExit
    file
  }
}

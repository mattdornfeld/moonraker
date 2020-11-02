package co.firstorderlabs.coinbaseml.common.featurizers

import java.util.logging.Logger

import co.firstorderlabs.coinbaseml.common.featurizers.OrderBookFeaturizer.OrderBookFeature
import co.firstorderlabs.coinbaseml.common.protos.ObservationRequest
import co.firstorderlabs.coinbaseml.common.utils.BufferUtils.FiniteQueue
import co.firstorderlabs.coinbaseml.common.utils.Utils.When
import co.firstorderlabs.common.protos.fakebase.OrderSide
import co.firstorderlabs.coinbaseml.fakebase._

case class OrderBookFeaturizerSnapshot(featureBuffer: List[OrderBookFeature])
    extends Snapshot

object OrderBookFeaturizer
    extends FeaturizerBase
    with Snapshotable[OrderBookFeaturizerSnapshot] {
  type OrderBookFeature = List[List[Double]]
  private val featureBuffer = new FiniteQueue[OrderBookFeature](0)
  private val logger = Logger.getLogger(OrderBookFeaturizer.toString)

  def getArrayOfZeros(height: Int, width: Int): OrderBookFeature = {
    (for (_ <- 0 until height) yield List.fill(width)(0.0)).toList
  }

  def getBestBidsAsksArrayOverTime(
      orderBookDepth: Int
  ): List[OrderBookFeature] = {
    val zerosArray = getArrayOfZeros(orderBookDepth, 4)
    featureBuffer.toList.padTo(SnapshotBuffer.maxSize, zerosArray)
  }

  def getBestBidsAsksArray(
      orderBookDepth: Int,
      normalize: Boolean = false
  ): OrderBookFeature = {
    val bestAsks =
      getBestPriceVolumes(
        Exchange.getOrderBook(OrderSide.sell),
        orderBookDepth,
        false,
        normalize
      )
    val bestBids =
      getBestPriceVolumes(
        Exchange.getOrderBook(OrderSide.buy),
        orderBookDepth,
        true,
        normalize
      )

    bestAsks
      .zip(bestBids)
      .map(item => List(item._1._1, item._1._2, item._2._1, item._2._2))
  }

  def getBestPriceVolumes(
      orderBook: OrderBook,
      orderBookDepth: Int,
      reverse: Boolean,
      normalize: Boolean = false
  ): List[(Double, Double)] = {
    orderBook
      .aggregateToMap(orderBookDepth, reverse)
      .toList
      .sortBy(item => item._1)
      .when(reverse)(_.reverse)
      .map(item =>
        (item._1.toDouble, item._2.whenElse(normalize)(_.normalize, _.toDouble))
      )
      .padTo(orderBookDepth, (0.0, 0.0))
  }

  def construct(observationRequest: ObservationRequest): List[Double] = {
    getBestBidsAsksArrayOverTime(
      observationRequest.orderBookDepth
    ).flatten.flatten
  }

  def start(snapshotBufferSize: Int): Unit = {
    clear
    featureBuffer.setMaxSize(snapshotBufferSize)
  }

  def step: Unit = {
    val observationRequest = Exchange.getSimulationMetadata.observationRequest
    val startTime = System.currentTimeMillis
    featureBuffer enqueue getBestBidsAsksArray(
      observationRequest.orderBookDepth,
      observationRequest.normalize
    )
    val endTime = System.currentTimeMillis

    logger.fine(s"OrderBookFeaturizer.step took ${endTime - startTime} ms")
  }

  override def clear: Unit = featureBuffer.clear

  override def createSnapshot: OrderBookFeaturizerSnapshot =
    OrderBookFeaturizerSnapshot(featureBuffer.toList)

  override def isCleared: Boolean = featureBuffer.isEmpty

  override def restore(snapshot: OrderBookFeaturizerSnapshot): Unit = {
    clear
    snapshot.featureBuffer.foreach(feature => featureBuffer.enqueue(feature))
  }
}

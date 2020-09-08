package co.firstorderlabs.common.featurizers

import co.firstorderlabs.common.protos.ObservationRequest
import co.firstorderlabs.common.utils.Utils.When
import co.firstorderlabs.fakebase.protos.fakebase.OrderSide
import co.firstorderlabs.fakebase.{OrderBook, SimulationSnapshot, SnapshotBuffer}

object OrderBookFeaturizer extends FeaturizerBase {
  def getArrayOfZeros(height: Int, width: Int): List[List[Double]] = {
    (for (_ <- 0 until height) yield List.fill(width)(0.0)).toList
  }

  def getOrderBookFromSnapshot(snapshot: SimulationSnapshot,
                               orderSide: OrderSide): OrderBook = {
    new OrderBook(
      Some(
        snapshot.matchingEngineSnapshot
          .whenElse(orderSide.isbuy)(
            _.buyOrderBookSnapshot,
            _.sellOrderBookSnapshot
          )
      )
    )
  }

  def getBestBidsAsksArrayOverTime(
    orderBookDepth: Int,
    normalize: Boolean = false,
  ): List[List[List[Double]]] = {
    SnapshotBuffer.toList
      .map(snapshot => getBestBidsAsksArray(snapshot, orderBookDepth, normalize))
      .padTo(SnapshotBuffer.maxSize, getArrayOfZeros(orderBookDepth, 4))
  }

  def getBestBidsAsksArray(snapshot: SimulationSnapshot,
                           orderBookDepth: Int,
                           normalize: Boolean = false): List[List[Double]] = {
    val buyOrderBook = getOrderBookFromSnapshot(snapshot, OrderSide.buy)
    val sellOrderBook = getOrderBookFromSnapshot(snapshot, OrderSide.sell)
    val bestAsks = getBestPriceVolumes(sellOrderBook, orderBookDepth, false, normalize)
    val bestBids = getBestPriceVolumes(buyOrderBook, orderBookDepth, true, normalize)

    bestAsks
      .zip(bestBids)
      .map(item => List(item._1._1, item._1._2, item._2._1, item._2._2))
  }

  def getBestPriceVolumes(orderBook: OrderBook,
                          orderBookDepth: Int,
                          reverse: Boolean,
                          normalize: Boolean = false): List[(Double, Double)] = {
    orderBook
      .aggregateToMap(orderBookDepth, reverse)
      .toList
      .sortBy(item => item._1)
      .when(reverse)(_.reverse)
      .map(item => (item._1.toDouble, item._2.whenElse(normalize)(_.normalize, _.toDouble)))
      .padTo(orderBookDepth, (0.0, 0.0))
  }

  def construct(observationRequest: ObservationRequest): List[Double] = {
    getBestBidsAsksArrayOverTime(observationRequest.orderBookDepth, observationRequest.normalize).flatten.flatten
  }
}

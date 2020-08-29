package co.firstorderlabs.common.featurizers

import co.firstorderlabs.common.protos.featurizer.ObservationRequest
import co.firstorderlabs.fakebase.protos.fakebase.OrderSide
import co.firstorderlabs.fakebase.{OrderBook, SimulationSnapshot, SnapshotBuffer}

object OrderBookFeaturizer extends FeaturizerBase {

  implicit class When[A, B](a: A) {
    def when(condition: Boolean)(f: A => A): A = if (condition) f(a) else a

    def whenElse(condition: Boolean)(f: A => B, g: A => B): B =
      if (condition) f(a) else g(a)
  }

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
    orderBookDepth: Int
  ): List[List[List[Double]]] = {
    SnapshotBuffer.toList.reverse
      .map(snapshot => getBestBidsAsksArray(snapshot, orderBookDepth))
      .padTo(SnapshotBuffer.getMaxSize, getArrayOfZeros(orderBookDepth, 4))
  }

  def getBestBidsAsksArray(snapshot: SimulationSnapshot,
                           orderBookDepth: Int): List[List[Double]] = {
    val buyOrderBook = getOrderBookFromSnapshot(snapshot, OrderSide.buy)
    val sellOrderBook = getOrderBookFromSnapshot(snapshot, OrderSide.sell)
    val bestAsks = getBestPriceVolumes(sellOrderBook, orderBookDepth, false)
    val bestBids = getBestPriceVolumes(buyOrderBook, orderBookDepth, true)

    bestAsks
      .zip(bestBids)
      .map(item => List(item._1._1, item._1._2, item._2._1, item._2._2))
  }

  def getBestPriceVolumes(orderBook: OrderBook,
                          orderBookDepth: Int,
                          reverse: Boolean): List[(Double, Double)] = {
    orderBook
      .aggregateToMap(orderBookDepth)
      .toList
      .sortBy(item => item._1)
      .when(reverse)(_.reverse)
      .map(item => (item._1.toDouble, item._2.toDouble))
      .padTo(orderBookDepth, (0.0, 0.0))
  }

  def construct(observationRequest: ObservationRequest): List[Double] = {
    getBestBidsAsksArrayOverTime(observationRequest.orderBookDepth).flatten.flatten
  }
}

package co.firstorderlabs.coinbaseml.common.featurizers

import co.firstorderlabs.coinbaseml.common.Configs.logLevel
import co.firstorderlabs.coinbaseml.common.featurizers.OrderBookVectorizer.{
  OrderBookFeature,
  getFeaturizerConfigs
}
import co.firstorderlabs.coinbaseml.common.utils.BufferUtils.FiniteQueue
import co.firstorderlabs.coinbaseml.common.utils.Utils.When
import co.firstorderlabs.coinbaseml.fakebase._
import co.firstorderlabs.common.protos.environment.ObservationRequest
import co.firstorderlabs.common.types.Featurizers.OrderBookVectorizerConfigs

import java.util.logging.Logger

final case class OrderBookVectorizerState(
    featureBuffer: FiniteQueue[OrderBookFeature]
) extends State[OrderBookVectorizerState] {
  override val companion = OrderBookVectorizerState

  override def createSnapshot(implicit
      simulationState: SimulationState
  ): OrderBookVectorizerState = {
    val orderBookFeaturizerState =
      OrderBookVectorizerState.create(simulationState.simulationMetadata)
    orderBookFeaturizerState.featureBuffer.addAll(featureBuffer.iterator)
    orderBookFeaturizerState
  }

}

object OrderBookVectorizerState
    extends StateCompanion[OrderBookVectorizerState] {
  override def create(implicit
      simulationMetadata: SimulationMetadata
  ): OrderBookVectorizerState =
    OrderBookVectorizerState(
      new FiniteQueue[OrderBookFeature](
        getFeaturizerConfigs(
          simulationMetadata.observationRequest
        ).featureBufferSize
      )
    )

  override def fromSnapshot(
      snapshot: OrderBookVectorizerState
  ): OrderBookVectorizerState = {
    val orderBookFeaturizerState = OrderBookVectorizerState(
      new FiniteQueue[OrderBookFeature](snapshot.featureBuffer.getMaxSize)
    )
    snapshot.featureBuffer.foreach(feature =>
      orderBookFeaturizerState.featureBuffer.enqueue(feature)
    )
    orderBookFeaturizerState
  }
}

object OrderBookVectorizer extends VectorizerBase {
  type OrderBookFeature = List[List[Double]]
  private val logger = Logger.getLogger(OrderBookVectorizer.toString)
  logger.setLevel(logLevel)

  def getFeaturizerConfigs(
      observationRequest: ObservationRequest
  ): OrderBookVectorizerConfigs =
    observationRequest.featurizerConfigs match {
      case featurizerConfigs: OrderBookVectorizerConfigs => featurizerConfigs
    }

  def getArrayOfZeros(height: Int, width: Int): OrderBookFeature = {
    (for (_ <- 0 until height) yield List.fill(width)(0.0)).toList
  }

  def getBestBidsAsksArrayOverTime(
      orderBookDepth: Int
  )(implicit
      featurizerState: OrderBookVectorizerState
  ): List[OrderBookFeature] = {
    val zerosArray = getArrayOfZeros(orderBookDepth, 4)
    featurizerState.featureBuffer.toList
      .padTo(featurizerState.featureBuffer.getMaxSize, zerosArray)
  }

  def getBestBidsAsksArray(
      orderBookDepth: Int,
      normalize: Boolean = false
  )(implicit matchingEngineState: MatchingEngineState): OrderBookFeature = {
    val bestAsks =
      getBestPriceVolumes(
        orderBookDepth,
        false,
        normalize
      )(matchingEngineState.sellOrderBookState)
    val bestBids =
      getBestPriceVolumes(
        orderBookDepth,
        true,
        normalize
      )(matchingEngineState.buyOrderBookState)

    bestAsks
      .zip(bestBids)
      .map(item => List(item._1._1, item._1._2, item._2._1, item._2._2))
  }

  def getBestPriceVolumes(
      orderBookDepth: Int,
      reverse: Boolean,
      normalize: Boolean = false
  )(implicit orderBookState: OrderBookState): List[(Double, Double)] = {
    OrderBook
      .aggregateToMap(orderBookDepth, reverse)
      .toList
      .sortBy(item => item._1)
      .when(reverse)(_.reverse)
      .map(item =>
        (item._1.toDouble, item._2.whenElse(normalize)(_.normalize, _.toDouble))
      )
      .padTo(orderBookDepth, (0.0, 0.0))
  }

  def featurizerState(implicit
      simulationState: SimulationState
  ): HasOrderBookVectorizerState =
    simulationState.environmentState.featurizerState match {
      case featurizerState: HasOrderBookVectorizerState => featurizerState
    }

  override def construct(
      observationRequest: ObservationRequest
  )(implicit simulationState: SimulationState): List[Double] =
    getBestBidsAsksArrayOverTime(
      getFeaturizerConfigs(observationRequest).orderBookDepth
    )(featurizerState.orderBookVectorizerState).flatten.flatten

  override def step(implicit simulationState: SimulationState): Unit = {
    val observationRequest =
      simulationState.simulationMetadata.observationRequest
    val startTime = System.currentTimeMillis
    simulationState.environmentState.featurizerState match {
      case featurizerState: HasOrderBookVectorizerState =>
        featurizerState.orderBookVectorizerState.featureBuffer enqueue getBestBidsAsksArray(
          getFeaturizerConfigs(observationRequest).orderBookDepth,
          observationRequest.normalize
        )(simulationState.matchingEngineState)
    }
    val endTime = System.currentTimeMillis

    logger.fine(s"OrderBookFeaturizer.step took ${endTime - startTime} ms")
  }
}

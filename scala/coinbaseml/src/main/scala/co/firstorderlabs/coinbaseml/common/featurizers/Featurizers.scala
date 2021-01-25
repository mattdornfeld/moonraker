package co.firstorderlabs.coinbaseml.common.featurizers

import co.firstorderlabs.coinbaseml.common.utils.ArrowUtils.readVectorFromSocket
import co.firstorderlabs.coinbaseml.fakebase.{
  SimulationMetadata,
  SimulationState
}
import co.firstorderlabs.common.protos.environment.{
  ObservationRequest,
  Featurizer => FeaturizerProto
}
import co.firstorderlabs.common.types.Types.Features
import org.apache.arrow.memory.RootAllocator

sealed trait FeaturizerBase {
  protected val allocator = new RootAllocator()

  def construct(observationRequest: ObservationRequest)(implicit
      simulationState: SimulationState
  ): Features

  def constructFromArrowSocket(implicit
      simulationMetadata: SimulationMetadata
  ): Features

  def step(implicit simulationState: SimulationState): Unit
}

object FeaturizerBase {
  def getFeaturizer(featurizerProto: FeaturizerProto): FeaturizerBase = {
    featurizerProto match {
      case FeaturizerProto.TimeSeriesOrderBook => TimeSeriesOrderBook
      case FeaturizerProto.NoOp                => NoOp
      case _ =>
        throw new IllegalArgumentException(s"$featurizerProto not recognized")
    }
  }
}

object NoOp extends FeaturizerBase {
  override def construct(observationRequest: ObservationRequest)(implicit
      simulationState: SimulationState
  ): Features = Features(Map())

  override def constructFromArrowSocket(implicit
      simulationMetadata: SimulationMetadata
  ): Features = Features(Map())

  override def step(implicit simulationState: SimulationState): Unit = {}
}

object TimeSeriesOrderBook extends FeaturizerBase {
  override def construct(
      observationRequest: ObservationRequest
  )(implicit simulationState: SimulationState): Features = {
    val featuresMap = Map(
      "account" -> AccountVectorizer.construct(observationRequest),
      "orderBook" -> OrderBookVectorizer.construct(observationRequest),
      "timeSeries" -> TimeSeriesVectorizer.construct(observationRequest)
    )

    Features(featuresMap)
  }

  override def constructFromArrowSocket(implicit
      simulationMetadata: SimulationMetadata
  ): Features = {
    val features = readVectorFromSocket
    val featuresMap = Map(
      "account" -> features(0),
      "orderBook" -> features(1),
      "timeSeries" -> features(2)
    )

    Features(featuresMap)
  }

  override def step(implicit simulationState: SimulationState): Unit = {
    OrderBookVectorizer.step
    TimeSeriesVectorizer.step
  }
}

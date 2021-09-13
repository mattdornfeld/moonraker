package co.firstorderlabs.coinbaseml.common.featurizers

import co.firstorderlabs.coinbaseml.common.utils.ArrowUtils.readVectorFromSocket
import co.firstorderlabs.coinbaseml.fakebase.{
  SimulationMetadata,
  SimulationState,
  State,
  StateCompanion
}
import co.firstorderlabs.common.protos.environment.ObservationRequest
import co.firstorderlabs.common.protos.featurizers.{
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
      case FeaturizerProto.OrderBook           => OrderBook
      case FeaturizerProto.NoOp                => NoOp
      case _ =>
        throw new IllegalArgumentException(s"$featurizerProto not recognized")
    }
  }
}

trait FeaturizerStateBase {
  def createSnapshot(implicit
      simulationState: SimulationState
  ): FeaturizerStateBase
}

trait FeaturizerStateBaseCompanion {
  def fromSnapshot(snapshot: FeaturizerStateBase): FeaturizerStateBase
}

object FeaturizerStateBase {
  def create(implicit
      simulationMetadata: SimulationMetadata
  ): FeaturizerStateBase =
    simulationMetadata.observationRequest.featurizer match {
      case FeaturizerProto.NoOp => NoOp.FeaturizerStateCompanion.create
      case FeaturizerProto.OrderBook =>
        OrderBook.FeaturizerStateCompanion.create
      case FeaturizerProto.TimeSeriesOrderBook =>
        TimeSeriesOrderBook.FeaturizerStateCompanion.create
    }

  def fromSnapshot(snapshot: FeaturizerStateBase): FeaturizerStateBase = {
    snapshot match {
      case snapshot: NoOp.FeaturizerState =>
        snapshot.companion.fromSnapshot(snapshot)
      case snapshot: OrderBook.FeaturizerState =>
        snapshot.companion.fromSnapshot(snapshot)
      case snapshot: TimeSeriesOrderBook.FeaturizerState =>
        snapshot.companion.fromSnapshot(snapshot)
    }
  }
}

trait HasOrderBookVectorizerState {
  val orderBookVectorizerState: OrderBookVectorizerState
}

trait HasTimeSeriesVectorizerState {
  val timeSeriesVectorizerState: TimeSeriesVectorizerState
}

object NoOp extends FeaturizerBase {
  class FeaturizerState
      extends FeaturizerStateBase
      with State[FeaturizerState] {
    override val companion = FeaturizerStateCompanion

    override def createSnapshot(implicit
        simulationState: SimulationState
    ): FeaturizerState = new FeaturizerState
  }

  object FeaturizerStateCompanion extends StateCompanion[FeaturizerState] {
    override def create(implicit
        simulationMetadata: SimulationMetadata
    ): FeaturizerState = new FeaturizerState

    override def fromSnapshot(snapshot: FeaturizerState): FeaturizerState =
      snapshot
  }

  override def construct(observationRequest: ObservationRequest)(implicit
      simulationState: SimulationState
  ): Features = Features(Map())

  override def constructFromArrowSocket(implicit
      simulationMetadata: SimulationMetadata
  ): Features = Features(Map())

  override def step(implicit simulationState: SimulationState): Unit = {}
}

object OrderBook extends FeaturizerBase {
  case class FeaturizerState(orderBookVectorizerState: OrderBookVectorizerState)
      extends FeaturizerStateBase
      with HasOrderBookVectorizerState
      with State[FeaturizerState] {
    override val companion = FeaturizerStateCompanion

    override def createSnapshot(implicit
        simulationState: SimulationState
    ): FeaturizerState =
      simulationState.environmentState.featurizerState match {
        case featurizerState: FeaturizerState =>
          FeaturizerState(
            featurizerState.orderBookVectorizerState.createSnapshot
          )
      }
  }

  object FeaturizerStateCompanion extends StateCompanion[FeaturizerState] {
    override def create(implicit
        simulationMetadata: SimulationMetadata
    ): FeaturizerState = FeaturizerState(OrderBookVectorizerState.create)

    override def fromSnapshot(snapshot: FeaturizerState): FeaturizerState =
      snapshot match {
        case snapshot: FeaturizerState =>
          FeaturizerState(
            OrderBookVectorizerState
              .fromSnapshot(snapshot.orderBookVectorizerState)
          )
      }
  }

  override def construct(
      observationRequest: ObservationRequest
  )(implicit simulationState: SimulationState): Features = {
    val featuresMap = Map(
      "orderBook" -> OrderBookVectorizer.construct(observationRequest)
    )

    Features(featuresMap)
  }

  override def constructFromArrowSocket(implicit
      simulationMetadata: SimulationMetadata
  ): Features = {
    val features = readVectorFromSocket
    val featuresMap = Map(
      "orderBook" -> features(0)
    )

    Features(featuresMap)
  }

  override def step(implicit simulationState: SimulationState): Unit = {
    OrderBookVectorizer.step
  }
}

object TimeSeriesOrderBook extends FeaturizerBase {
  case class FeaturizerState(
      orderBookVectorizerState: OrderBookVectorizerState,
      timeSeriesVectorizerState: TimeSeriesVectorizerState
  ) extends FeaturizerStateBase
      with HasOrderBookVectorizerState
      with HasTimeSeriesVectorizerState
      with State[FeaturizerState] {
    override val companion: StateCompanion[FeaturizerState] =
      FeaturizerStateCompanion

    override def createSnapshot(implicit
        simulationState: SimulationState
    ): FeaturizerState =
      simulationState.environmentState.featurizerState match {
        case featurizerState: FeaturizerState =>
          FeaturizerState(
            featurizerState.orderBookVectorizerState.createSnapshot,
            featurizerState.timeSeriesVectorizerState.createSnapshot
          )
      }
  }

  object FeaturizerStateCompanion extends StateCompanion[FeaturizerState] {
    override def create(implicit
        simulationMetadata: SimulationMetadata
    ): FeaturizerState =
      FeaturizerState(
        OrderBookVectorizerState.create,
        TimeSeriesVectorizerState.create
      )

    override def fromSnapshot(snapshot: FeaturizerState): FeaturizerState =
      snapshot match {
        case snapshot: FeaturizerState =>
          FeaturizerState(
            OrderBookVectorizerState
              .fromSnapshot(snapshot.orderBookVectorizerState),
            TimeSeriesVectorizerState.fromSnapshot(
              snapshot.timeSeriesVectorizerState
            )
          )
      }
  }

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

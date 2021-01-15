package co.firstorderlabs.coinbaseml.common.featurizers

import java.util.logging.Logger

import co.firstorderlabs.coinbaseml.common.featurizers.Aggregators._
import co.firstorderlabs.coinbaseml.common.featurizers.TimeSeriesFeaturizer.TimeSeriesFeature
import co.firstorderlabs.coinbaseml.common.utils.BufferUtils.FiniteQueue
import co.firstorderlabs.coinbaseml.fakebase._
import co.firstorderlabs.common.protos.environment.ObservationRequest
import co.firstorderlabs.common.protos.events.{
  BuyLimitOrder,
  Cancellation,
  Match,
  SellLimitOrder
}
import co.firstorderlabs.common.types.Events.{
  Event,
  OrderEvent,
  SpecifiesPrice,
  SpecifiesSize
}

final case class TimeSeriesFeaturizerState(
    featureBuffer: FiniteQueue[TimeSeriesFeature]
) extends co.firstorderlabs.coinbaseml.fakebase.State[TimeSeriesFeaturizerState] {
  override val companion = TimeSeriesFeaturizerState

  override def createSnapshot(implicit
      simulationMetadata: SimulationMetadata
  ): TimeSeriesFeaturizerState = {
    val timeSeriesFeaturizerState = TimeSeriesFeaturizerState.create
    timeSeriesFeaturizerState.featureBuffer.addAll(featureBuffer.iterator)
    timeSeriesFeaturizerState
  }

}

object TimeSeriesFeaturizerState
    extends StateCompanion[TimeSeriesFeaturizerState] {
  override def create(implicit
      simulationMetadata: SimulationMetadata
  ): TimeSeriesFeaturizerState =
    TimeSeriesFeaturizerState(
      new FiniteQueue[TimeSeriesFeature](simulationMetadata.featureBufferSize)
    )

  override def fromSnapshot(
      snapshot: TimeSeriesFeaturizerState
  ): TimeSeriesFeaturizerState = {
    val timeSeriesFeaturizerState = TimeSeriesFeaturizerState(
      new FiniteQueue[TimeSeriesFeature](snapshot.featureBuffer.getMaxSize)
    )
    snapshot.featureBuffer.foreach(feature =>
      timeSeriesFeaturizerState.featureBuffer.enqueue(feature)
    )
    timeSeriesFeaturizerState
  }
}

object TimeSeriesFeaturizer extends FeaturizerBase {
  type TimeSeriesFeature = List[Double]
  private val logger = Logger.getLogger(TimeSeriesFeaturizer.toString)
  import co.firstorderlabs.coinbaseml.common.featurizers.Filters._
  import co.firstorderlabs.coinbaseml.common.featurizers.Mappers._

  val eventFilters = List(
    Filters.buyCancellationFilter,
    Filters.buyMatchFilter,
    Filters.buyOrderFilter,
    Filters.sellCancellationFilter,
    Filters.sellMatchFilter,
    Filters.sellOrderFilter
  )

  val specifiesPriceFilters = List(
    Filters.buyCancellationFilter,
    Filters.sellCancellationFilter,
    Filters.buyMatchFilter,
    Filters.sellMatchFilter,
    Filters.buyLimitOrderFilter,
    Filters.sellLimitOrderFilter
  )

  val specifiesSizeFilters = List(
    buyMatchFilter,
    sellMatchFilter,
    buyLimitOrderFilter,
    sellLimitOrderFilter
  )

  private val priceFeatureFunctions =
    List(RunningMean.apply _, RunningStandardDeviation.apply _)
  private val sizeFeatureFunctions =
    List(RunningMean.apply _, RunningStandardDeviation.apply _)

  val eventAggregators = (getCountAggregators
    ++ getPriceAggregators
    ++ getSizeAggregators)

  def getCountAggregators: List[Counter] = {
    eventFilters
      .map((_, eventCountMapper))
      .map(item => Counter(item._1, item._2))
  }

  def getPriceAggregators: List[EventAggregator] = {
    specifiesPriceFilters
      .map((_, priceMapper))
      .map(item => priceFeatureFunctions.map(f => f(item._1, item._2)))
      .flatten
  }

  def getSizeAggregators: List[EventAggregator] = {
    specifiesSizeFilters
      .map((_, sizeMapper))
      .map(item => sizeFeatureFunctions.map(f => f(item._1, item._2)))
      .flatten
  }

  def numEventCountFeatureChannels: Int = eventFilters.size

  def numPriceFeatureChannels: Int =
    priceFeatureFunctions.size * specifiesPriceFilters.size

  def numSizeFeatureChannels: Int =
    sizeFeatureFunctions.size * specifiesSizeFilters.size

  def numChannels: Int =
    numEventCountFeatureChannels + numPriceFeatureChannels + numSizeFeatureChannels

  override def construct(
      observationRequest: ObservationRequest
  )(implicit simulationState: SimulationState): List[Double] = {
    val featureBuffer = simulationState.environmentState.timeSeriesFeaturizerState.featureBuffer
    featureBuffer.toList.reverse.flatten
      .padTo(numChannels * featureBuffer.getMaxSize, 0.0)
  }

  override def step(implicit simulationState: SimulationState): Unit = {
    val startTime = System.currentTimeMillis()
    eventAggregators.foreach(_.clear)
    val events =
      simulationState.exchangeState.receivedEvents ++ simulationState.matchingEngineState.matches

    events.foreach { event =>
      eventAggregators.foreach { featureAggregator =>
        featureAggregator.update(event)
      }
    }

    simulationState.environmentState.timeSeriesFeaturizerState.featureBuffer enqueue eventAggregators.map(_.value)

    val endTime = System.currentTimeMillis()
    logger.fine(s"TimeSeriesFeaturizer.step took ${endTime - startTime} ms")
  }
}

object Filters {
  val buyCancellationFilter = (event: Event) =>
    event match {
      case event: Cancellation if event.side.isbuy => true
      case _                                       => false
    }
  val buyMatchFilter = (event: Event) =>
    event match {
      case event: Match if event.side.isbuy => true
      case _                                => false
    }
  val buyOrderFilter = (event: Event) =>
    event match {
      case event: OrderEvent if event.side.isbuy => true
      case _                                     => false
    }
  val buyLimitOrderFilter = (event: Event) =>
    event match {
      case _: BuyLimitOrder => true
      case _                => false
    }
  val sellCancellationFilter = (event: Event) =>
    event match {
      case event: Cancellation if event.side.issell => true
      case _                                        => false
    }
  val sellMatchFilter = (event: Event) =>
    event match {
      case event: Match if event.side.issell => true
      case _                                 => false
    }
  val sellOrderFilter = (event: Event) =>
    event match {
      case event: OrderEvent if event.side.issell => true
      case _                                      => false
    }
  val sellLimitOrderFilter = (event: Event) =>
    event match {
      case _: SellLimitOrder => true
      case _                 => false
    }
}

object Mappers {
  val eventCountMapper = (_: Event) => 1.0

  val priceMapper = (event: Event) =>
    event match {
      case event: SpecifiesPrice => event.price.toDouble
    }

  val sizeMapper = (event: Event) =>
    event match {
      case event: SpecifiesSize => event.size.toDouble
    }
}

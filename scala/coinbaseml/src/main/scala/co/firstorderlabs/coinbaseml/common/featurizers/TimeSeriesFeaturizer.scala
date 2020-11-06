package co.firstorderlabs.coinbaseml.common.featurizers

import java.util.logging.Logger

import co.firstorderlabs.coinbaseml.common.featurizers.Aggregators._
import co.firstorderlabs.coinbaseml.common.utils.BufferUtils.FiniteQueue
import co.firstorderlabs.coinbaseml.common.utils.Utils.getResult
import co.firstorderlabs.coinbaseml.fakebase._
import co.firstorderlabs.common.protos.environment.ObservationRequest
import co.firstorderlabs.common.protos.events.{BuyLimitOrder, Cancellation, Match, SellLimitOrder}
import co.firstorderlabs.common.types.Events.{Event, OrderEvent, SpecifiesPrice, SpecifiesSize}

case class TimeSeriesFeaturizerSnapshot(
    featureBuffer: List[TimeSeriesFeaturizer.TimeSeriesFeature]
) extends Snapshot

object TimeSeriesFeaturizer
    extends FeaturizerBase
    with Snapshotable[TimeSeriesFeaturizerSnapshot] {
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
  private val featureBuffer = new FiniteQueue[TimeSeriesFeature](0)

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

  def step: Unit = {
    val startTime = System.currentTimeMillis()
    eventAggregators.foreach(_.clear)
    val events = Exchange.getReceivedEvents ++ getResult(
      Exchange.getMatches(Constants.emptyProto)
    ).matchEvents

    events.foreach { event =>
      eventAggregators.foreach { featureAggregator =>
        featureAggregator.update(event)
      }
    }

    featureBuffer enqueue eventAggregators.map(_.value)

    val endTime = System.currentTimeMillis()
    logger.fine(s"TimeSeriesFeaturizer.step took ${endTime - startTime} ms")
  }

  def numEventCountFeatureChannels: Int = eventFilters.size

  def numPriceFeatureChannels: Int =
    priceFeatureFunctions.size * specifiesPriceFilters.size

  def numSizeFeatureChannels: Int =
    sizeFeatureFunctions.size * specifiesSizeFilters.size

  def numChannels: Int =
    numEventCountFeatureChannels + numPriceFeatureChannels + numSizeFeatureChannels

  def start(snapshotBufferSize: Int): Unit = {
    clear
    featureBuffer.setMaxSize(snapshotBufferSize)
  }

  override def clear: Unit = {
    featureBuffer.clear
    eventAggregators.foreach(_.clear)
  }

  override def createSnapshot: TimeSeriesFeaturizerSnapshot =
    TimeSeriesFeaturizerSnapshot(
      featureBuffer.toList
    )

  override def isCleared: Boolean = {
    featureBuffer.isEmpty
    eventAggregators.forall(_.value == 0.0)
  }

  override def restore(snapshot: TimeSeriesFeaturizerSnapshot): Unit = {
    clear
    snapshot.featureBuffer.foreach(feature => featureBuffer.enqueue(feature))
  }

  override def construct(observationRequest: ObservationRequest): List[Double] =
    featureBuffer.toList.reverse.flatten
      .padTo(numChannels * SnapshotBuffer.maxSize, 0.0)
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

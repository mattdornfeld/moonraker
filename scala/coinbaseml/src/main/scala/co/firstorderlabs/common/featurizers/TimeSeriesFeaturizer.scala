package co.firstorderlabs.common.featurizers

import co.firstorderlabs.common.protos.ObservationRequest
import co.firstorderlabs.fakebase.protos.fakebase.{
  BuyLimitOrder,
  Cancellation,
  Match,
  SellLimitOrder
}
import co.firstorderlabs.fakebase.types.Events.{
  Event,
  OrderEvent,
  SpecifiesPrice,
  SpecifiesSize
}
import co.firstorderlabs.fakebase.{SimulationSnapshot, SnapshotBuffer}

import scala.math.pow

object TimeSeriesFeaturizer extends FeaturizerBase {
  // Specify mappers for extracting quantities from receivedEvents
  private val priceMapper = (event: Event) =>
    event match {
      case event: SpecifiesPrice => event.price.toDouble
    }
  private val sizeMapper = (event: Event) =>
    event match {
      case event: SpecifiesSize => event.size.toDouble
    }

  // Specify filters for selecting values from receivedEvents
  private val buyCancellationFilter = (event: Event) =>
    event match {
      case event: Cancellation if event.side.isbuy => true
      case _                                       => false
    }
  private val buyMatchFilter = (event: Event) =>
    event match {
      case event: Match if event.side.isbuy => true
      case _                                => false
    }
  private val buyOrderFilter = (event: Event) =>
    event match {
      case event: OrderEvent if event.side.isbuy => true
      case _                                     => false
    }
  private val buyLimitOrderFilter = (event: Event) =>
    event match {
      case _: BuyLimitOrder => true
      case _                => false
    }
  private val sellCancellationFilter = (event: Event) =>
    event match {
      case event: Cancellation if event.side.issell => true
      case _                                        => false
    }
  private val sellMatchFilter = (event: Event) =>
    event match {
      case event: Match if event.side.issell => true
      case _                                 => false
    }
  private val sellOrderFilter = (event: Event) =>
    event match {
      case event: OrderEvent if event.side.issell => true
      case _                                      => false
    }
  private val sellLimitOrderFilter = (event: Event) =>
    event match {
      case _: SellLimitOrder => true
      case _                 => false
    }

  // Gather filters for values that will be aggregated similarly into lists
  private val eventFilters = List(
    buyCancellationFilter,
    buyMatchFilter,
    buyOrderFilter,
    sellCancellationFilter,
    sellMatchFilter,
    sellOrderFilter
  )
  private val specifiesPriceFilters = List(
    buyCancellationFilter,
    sellCancellationFilter,
    buyMatchFilter,
    sellMatchFilter,
    buyLimitOrderFilter,
    sellLimitOrderFilter
  )
  private val specifiesSizeFilters = List(
    buyMatchFilter,
    sellMatchFilter,
    buyLimitOrderFilter,
    sellLimitOrderFilter
  )

  private val priceFeatureFunctions = List(mean _, std _)
  private val sizeFeatureFunctions = List(mean _, std _)

  def mean(x: List[Double]): Double =
    if (x.size > 0)
      x.sum / x.size
    else 0.0

  def std(x: List[Double]): Double = {
    val mean_of_x_squared = mean(x.map(item => pow(item, 2)))
    val square_of_mean_of_x = pow(mean(x), 2)
    val variance = mean_of_x_squared - square_of_mean_of_x

    if (variance > 0) pow(variance, 0.5) else 0.0
  }

  def constructFeaturesFromSnapshot(
      simulationSnapshot: SimulationSnapshot
  ): List[Double] = {
    val events = getEvents(simulationSnapshot)
    constructEventCountFeatures(events) ++ constructPriceFeatures(
      events
    ) ++ constructSizeFeatures(
      events
    )
  }

  def construct(observationRequest: ObservationRequest): List[Double] =
    SnapshotBuffer.toList.reverse
      .map(constructFeaturesFromSnapshot)
      .flatten
      .padTo(numChannels * SnapshotBuffer.maxSize, 0.0)

  def constructEventCountFeatures(events: List[Event]): List[Double] =
    eventFilters.map(filter => events.filter(filter).size.toDouble)

  def constructPriceFeatures(events: List[Event]): List[Double] =
    specifiesPriceFilters.map { filter =>
      val eventPrices = events.filter(filter).map(priceMapper)
      priceFeatureFunctions.map(f => f(eventPrices))
    }.flatten

  def constructSizeFeatures(events: List[Event]): List[Double] =
    specifiesSizeFilters.map { filter =>
      val eventSizes = events.filter(filter).map(sizeMapper)
      sizeFeatureFunctions.map(f => f(eventSizes))
    }.flatten

  def getEvents(simulationSnapshot: SimulationSnapshot): List[Event] =
    simulationSnapshot.exchangeSnapshot.receivedEvents ++ simulationSnapshot.matchingEngineSnapshot.matches

  def numEventCountFeatureChannels: Int = eventFilters.size

  def numPriceFeatureChannels: Int =
    priceFeatureFunctions.size * specifiesPriceFilters.size

  def numSizeFeatureChannels: Int =
    sizeFeatureFunctions.size * specifiesSizeFilters.size

  def numChannels: Int =
    numEventCountFeatureChannels + numPriceFeatureChannels + numSizeFeatureChannels
}

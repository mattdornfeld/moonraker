package co.firstorderlabs.coinbaseml.common

import co.firstorderlabs.coinbaseml.common.utils.Utils.getResult
import co.firstorderlabs.coinbaseml.fakebase._
import co.firstorderlabs.common.protos.environment.{InfoDict, InfoDictKey}
import co.firstorderlabs.common.protos.events.Match
import co.firstorderlabs.common.types.Events.OrderEvent
import co.firstorderlabs.common.types.Types.SimulationId

final case class InfoAggregatorState(infoDict: InfoDict)
    extends State[InfoAggregatorState] {
  override val companion = InfoAggregatorState

  override def createSnapshot(implicit
      simulationMetadata: SimulationMetadata
  ): InfoAggregatorState =
    InfoAggregatorState(infoDict.clone)

}

object InfoAggregatorState extends StateCompanion[InfoAggregatorState] {
  override def create(implicit
      simulationMetadata: SimulationMetadata
  ): InfoAggregatorState = {
    val infoDict = new InfoDict
    infoDict.instantiate
    InfoAggregatorState(infoDict)
  }

  override def fromSnapshot(
      snapshot: InfoAggregatorState
  ): InfoAggregatorState = {
    val infoDict = new InfoDict
    snapshot.infoDict.foreach(item => infoDict.put(item._1, item._2))
    InfoAggregatorState(infoDict)
  }
}

object InfoAggregator {

  private val buyMatchFilter = (matchEvent: Match) =>
    matchEvent.side.isbuy && matchEvent.liquidity.ismaker || matchEvent.side.issell && matchEvent.liquidity.istaker
  private val buyOrderFilter = (orderEvent: OrderEvent) => orderEvent.side.isbuy
  private val sellMatchFilter = (matchEvent: Match) =>
    matchEvent.side.issell && matchEvent.liquidity.ismaker || matchEvent.side.isbuy && matchEvent.liquidity.istaker
  private val sellOrderFilter = (orderEvent: OrderEvent) =>
    orderEvent.side.issell

  private def incrementFeesPaid(
      matches: Seq[Match]
  )(implicit infoAggregatorState: InfoAggregatorState): Unit =
    Seq(
      (buyMatchFilter, InfoDictKey.buyFeesPaid),
      (sellMatchFilter, InfoDictKey.sellFeesPaid)
    ).foreach { item =>
      val feesPaid = matches
        .filter(item._1)
        .map(_.fee.toDouble)
        .reduceOption(_ + _)
        .getOrElse(0.0)
      infoAggregatorState.infoDict.increment(item._2, feesPaid)
    }

  private def incrementOrdersPlaced(implicit
      accountState: AccountState,
      infoAggregatorState: InfoAggregatorState
  ): Unit = {
    val placedOrders = Account.getReceivedOrders

    Seq(
      (buyOrderFilter, InfoDictKey.buyOrdersPlaced),
      (sellOrderFilter, InfoDictKey.sellOrdersPlaced)
    ).foreach { item =>
      val numOrdersPlaced = placedOrders.filter(item._1).size
      infoAggregatorState.infoDict.increment(item._2, numOrdersPlaced)
    }
  }

  private def incrementOrdersRejected(implicit
      accountState: AccountState,
      infoAggregatorState: InfoAggregatorState,
      simulationMetadata: SimulationMetadata
  ): Unit = {
    val currentTimeInterval = simulationMetadata.currentTimeInterval
    val numOrdersRejected = Account
      .getFilteredOrders((orderEvent: OrderEvent) =>
        orderEvent.orderStatus.isrejected && currentTimeInterval.contains(
          orderEvent.time
        )
      )
      .size

    infoAggregatorState.infoDict.increment(InfoDictKey.numOrdersRejected, numOrdersRejected)
  }

  private def incrementVolumeTraded(matches: Seq[Match])(implicit infoAggregatorState: InfoAggregatorState): Unit =
    Seq(
      (buyMatchFilter, InfoDictKey.buyVolumeTraded),
      (sellMatchFilter, InfoDictKey.sellVolumeTraded)
    ).foreach { item =>
      val volumeTraded =
        matches
          .filter(item._1)
          .map(_.quoteVolume.toDouble)
          .reduceOption(_ + _)
          .getOrElse(0.0)
      infoAggregatorState.infoDict.increment(item._2, volumeTraded)
    }

  private def updatePortfolioValue(implicit
      matchingEngineState: MatchingEngineState,
      infoAggregatorState: InfoAggregatorState,
      simulationMetadata: SimulationMetadata
  ): Unit = {
    val portfolioValue = matchingEngineState.currentPortfolioValue.getOrElse(
      simulationMetadata.initialQuoteFunds.toDouble
    )

    infoAggregatorState.infoDict.put(InfoDictKey.portfolioValue, portfolioValue)
  }

  private def updateRoi(implicit
      infoAggregatorState: InfoAggregatorState,
      matchingEngineState: MatchingEngineState
  ): Unit = {
    matchingEngineState.checkpointPortfolioValue
      .zip(matchingEngineState.currentPortfolioValue)
      .map { item =>
        val roi = (item._2 - item._1) / item._1
        infoAggregatorState.infoDict.put(InfoDictKey.roi, roi)
      }
  }

  def increment(key: InfoDictKey)(implicit infoAggregatorState: InfoAggregatorState): Unit = infoAggregatorState.infoDict(key) += 1

  def preStep(implicit simulationState: SimulationState): Unit = {
    // These methods must be called directly after orders are placed with Account
    incrementOrdersPlaced(simulationState.accountState, simulationState.environmentState.infoAggregatorState)
    incrementOrdersRejected(
      simulationState.accountState,
      simulationState.environmentState.infoAggregatorState,
      simulationState.simulationMetadata,
    )
  }

  def step(
      stepDuration: Double,
      dataGetDuration: Double,
      matchingEngineDuration: Double,
      environmentDuration: Double,
      numEvents: Double,
      simulationId: SimulationId
  )(implicit simulationState: SimulationState): Unit = {
    val matches = getResult(
      Account.getMatches(simulationId)
    ).matchEvents
    implicit val infoAggregatorState = simulationState.environmentState.infoAggregatorState
    val infoDict = infoAggregatorState.infoDict

    // Make sure to increment numSamples before calling incrementRunningMean
    infoDict.increment(InfoDictKey.numSamples, 1.0)
    List(
      (InfoDictKey.simulationStepDuration, stepDuration),
      (InfoDictKey.dataGetDuration, dataGetDuration),
      (InfoDictKey.matchingEngineDuration, matchingEngineDuration),
      (InfoDictKey.environmentDuration, environmentDuration),
      (InfoDictKey.numEvents, numEvents)
    ).foreach { item => infoDict.incrementRunningMean(item._1, item._2) }

    incrementFeesPaid(matches)
    incrementVolumeTraded(matches)
    updatePortfolioValue(
      simulationState.matchingEngineState,
      infoAggregatorState,
      simulationState.simulationMetadata
    )
    updateRoi(infoAggregatorState, simulationState.matchingEngineState)
  }
}

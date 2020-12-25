package co.firstorderlabs.coinbaseml.common

import co.firstorderlabs.coinbaseml.common.utils.Utils.getResult
import co.firstorderlabs.coinbaseml.fakebase._
import co.firstorderlabs.common.protos.environment.{InfoDict, InfoDictKey}
import co.firstorderlabs.common.protos.events.Match
import co.firstorderlabs.common.types.Events.OrderEvent

final case class InfoAggregatorSnapshot(infoDict: InfoDict) extends Snapshot

object InfoAggregator extends Snapshotable[InfoAggregatorSnapshot] {
  private val infoDict = new InfoDict
  instantiateInfoDict

  private val buyMatchFilter = (matchEvent: Match) =>
    matchEvent.side.isbuy && matchEvent.liquidity.ismaker || matchEvent.side.issell && matchEvent.liquidity.istaker
  private val buyOrderFilter = (orderEvent: OrderEvent) => orderEvent.side.isbuy
  private val sellMatchFilter = (matchEvent: Match) =>
    matchEvent.side.issell && matchEvent.liquidity.ismaker || matchEvent.side.isbuy && matchEvent.liquidity.istaker
  private val sellOrderFilter = (orderEvent: OrderEvent) =>
    orderEvent.side.issell

  private def instantiateInfoDict: Unit = infoDict.instantiate

  private def incrementFeesPaid(matches: Seq[Match]): Unit =
    Seq(
      (buyMatchFilter, InfoDictKey.buyFeesPaid),
      (sellMatchFilter, InfoDictKey.sellFeesPaid)
    ).foreach { item =>
      val feesPaid = matches
        .filter(item._1)
        .map(_.fee.toDouble)
        .reduceOption(_ + _)
        .getOrElse(0.0)
      infoDict.increment(item._2, feesPaid)
    }

  private def incrementOrdersPlaced: Unit = {
    val placedOrders = Account.getReceivedOrders

    Seq(
      (buyOrderFilter, InfoDictKey.buyOrdersPlaced),
      (sellOrderFilter, InfoDictKey.sellOrdersPlaced)
    ).foreach { item =>
      val numOrdersPlaced = placedOrders.filter(item._1).size
      infoDict.increment(item._2, numOrdersPlaced)
    }
  }

  private def incrementOrdersRejected: Unit = {
    val currentTimeInterval = Exchange.getSimulationMetadata.currentTimeInterval
    val numOrdersRejected = Account
      .getFilteredOrders((orderEvent: OrderEvent) =>
        orderEvent.orderStatus.isrejected && currentTimeInterval.contains(
          orderEvent.time
        )
      )
      .size

    infoDict.increment(InfoDictKey.numOrdersRejected, numOrdersRejected)
  }

  private def incrementVolumeTraded(matches: Seq[Match]): Unit =
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
      infoDict.increment(item._2, volumeTraded)
    }

  private def updatePortfolioValue: Unit = {
    val simulationMetadata = Exchange.getSimulationMetadata
    val portfolioValue = MatchingEngine.currentPortfolioValue.getOrElse(
      simulationMetadata.initialQuoteFunds.toDouble
    )

    infoDict.put(InfoDictKey.portfolioValue, portfolioValue)
  }

  private def updateRoi: Unit = {
    MatchingEngine.checkpointPortfolioValue
      .zip(MatchingEngine.currentPortfolioValue)
      .map { item =>
        val roi = (item._2 - item._1) / item._1
        infoDict.put(InfoDictKey.roi, roi)
      }
  }

  def getInfoDict: InfoDict = infoDict

  def increment(key: InfoDictKey): Unit = infoDict(key) += 1

  def preStep: Unit = {
    // These methods must be directly after orders are placed with Account
    incrementOrdersPlaced
    incrementOrdersRejected
  }

  def step(
      stepDuration: Double,
      dataGetDuration: Double,
      matchingEngineDuration: Double,
      environmentDuration: Double,
      numEvents: Double
  ): Unit = {
    val matches = getResult(
      Account.getMatches(Constants.emptyProto)
    ).matchEvents

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
    updatePortfolioValue
    updateRoi
  }

  def clear: Unit = {
    infoDict.clear
    instantiateInfoDict
  }

  def isCleared: Boolean =
    (InfoDictKey.values.forall(key => infoDict.contains(key))
      && infoDict.values.forall(_ == 0.0))

  override def createSnapshot: InfoAggregatorSnapshot =
    InfoAggregatorSnapshot(infoDict.clone)

  override def restore(snapshot: InfoAggregatorSnapshot): Unit = {
    clear
    snapshot.infoDict.foreach(item => infoDict.put(item._1, item._2))
  }
}

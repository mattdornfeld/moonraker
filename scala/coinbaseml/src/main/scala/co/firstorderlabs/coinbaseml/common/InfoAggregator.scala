package co.firstorderlabs.coinbaseml.common

import co.firstorderlabs.coinbaseml.common.rewards.ReturnRewardStrategy
import co.firstorderlabs.coinbaseml.common.utils.Utils.getResult
import co.firstorderlabs.coinbaseml.fakebase.{Account, Constants, Exchange}
import co.firstorderlabs.common.protos.environment.{InfoDict, InfoDictKey}
import co.firstorderlabs.common.protos.events.Match
import co.firstorderlabs.common.types.Events.OrderEvent


object InfoAggregator {
  private val infoDict = new InfoDict
  instantiateInfoDict

  private val buyMatchFilter = (matchEvent: Match) => matchEvent.side.isbuy
  private val buyOrderFilter = (orderEvent: OrderEvent) => orderEvent.side.isbuy
  private val sellMatchFilter = (matchEvent: Match) => matchEvent.side.issell
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
          .map(_.size.toDouble)
          .reduceOption(_ + _)
          .getOrElse(0.0)
      infoDict.increment(item._2, volumeTraded)
    }

  private def updatePortfolioValue: Unit = {
    val simulationMetadata = Exchange.getSimulationMetadata
    val portfolioValue =
      if (simulationMetadata.currentStep > 0)
        ReturnRewardStrategy.currentPortfolioValue
      else simulationMetadata.initialQuoteFunds.toDouble

    infoDict.put(InfoDictKey.portfolioValue, portfolioValue)
  }

  def getInfoDict: InfoDict = infoDict

  def preStep: Unit = {
    incrementOrdersPlaced
    incrementOrdersRejected
  }

  def step: Unit = {
    val matches = getResult(
      Account.getMatches(Constants.emptyProto)
    ).matchEvents

    incrementFeesPaid(matches)
    incrementVolumeTraded(matches)
    updatePortfolioValue
  }

  def clear: Unit = {
    infoDict.clear
    instantiateInfoDict
  }

  def isCleared: Boolean =
    (InfoDictKey.values.forall(key => infoDict.contains(key))
      && infoDict.values.forall(_ == 0.0))
}

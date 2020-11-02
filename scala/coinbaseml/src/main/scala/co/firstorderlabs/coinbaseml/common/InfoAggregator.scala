package co.firstorderlabs.coinbaseml.common

import co.firstorderlabs.coinbaseml.common.protos.{InfoDictKey, InfoDict => InfoDictProto}
import co.firstorderlabs.coinbaseml.common.rewards.ReturnRewardStrategy
import co.firstorderlabs.coinbaseml.common.utils.Utils.getResult
import co.firstorderlabs.coinbaseml.fakebase.{Account, Constants, Exchange, SnapshotBuffer}
import co.firstorderlabs.common.protos.fakebase.Match
import co.firstorderlabs.coinbaseml.fakebase.types.Events.OrderEvent

import scala.collection.mutable

class InfoDict extends mutable.HashMap[InfoDictKey, Double] {
  def increment(key: InfoDictKey, incrementValue: Double): Unit = {
    val currentValue = getOrElse(key, 0.0)
    update(key, currentValue + incrementValue)
  }

  def instantiate: Unit =
    InfoDictKey.values.foreach { key =>
      put(key, 0.0)
    }
}

object InfoDict {
  def fromProto(infoDictProto: InfoDictProto): InfoDict = {
    val infoDict = new InfoDict
    infoDictProto.infoDict
      .map { item =>
        val key = InfoDictKey.fromName(item._1) match {
          case Some(key) => key
          case None =>
            throw new IllegalArgumentException(
              s"Key ${item._1} is not a member of InfoDictKeys"
            )
        }
        (key, item._2)
      }
      .foreach(item => infoDict.put(item._1, item._2))

    infoDict
  }

  def toProto(infoDict: InfoDict): InfoDictProto = {
    InfoDictProto(infoDict.map(item => (item._1.name, item._2)).toMap)
  }
}

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
      if (SnapshotBuffer.size > 0)
        ReturnRewardStrategy.calcPortfolioValue(
          simulationMetadata.currentTimeInterval
        )
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

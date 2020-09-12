package co.firstorderlabs.common

import co.firstorderlabs.common.rewards.ReturnRewardStrategy
import co.firstorderlabs.common.utils.Utils.getResult
import co.firstorderlabs.fakebase.protos.fakebase.Match
import co.firstorderlabs.fakebase.types.Events.OrderEvent
import co.firstorderlabs.fakebase._

import scala.collection.mutable.HashMap

case class InfoAggregatorSnapshot(infoDict: HashMap[String, Double])
    extends Snapshot

object InfoDictKeys {
  val buyFeesPaid = "buyFeesPaid"
  val buyOrdersPlaced = "buyOrdersPlaced"
  val buyVolumeTraded = "buyVolumeTraded"
  val numOrdersRejected = "numOrdersRejected"
  val portfolioValue = "portfolioValue"
  val sellFeesPaid = "sellFeesPaid"
  val sellOrdersPlaced = "sellOrdersPlaced"
  val sellVolumeTraded = "sellVolumeTraded"

  def toList: List[String] =
    List(
      buyFeesPaid,
      buyOrdersPlaced,
      buyVolumeTraded,
      numOrdersRejected,
      portfolioValue,
      sellFeesPaid,
      sellOrdersPlaced,
      sellVolumeTraded
    )
}

object InfoAggregator {
  implicit class HashMapIncrementer(hashMap: HashMap[String, Double]) {
    def increment(key: String, incrementValue: Double): Unit = {
      val currentValue = hashMap.getOrElse(key, 0.0)
      hashMap.update(key, currentValue + incrementValue)
    }
  }

  private val infoDict = new HashMap[String, Double]
  instantiateInfoDict

  private val buyMatchFilter = (matchEvent: Match) => matchEvent.side.isbuy
  private val buyOrderFilter = (orderEvent: OrderEvent) => orderEvent.side.isbuy
  private val sellMatchFilter = (matchEvent: Match) => matchEvent.side.issell
  private val sellOrderFilter = (orderEvent: OrderEvent) => orderEvent.side.issell

  private def instantiateInfoDict: Unit =
    InfoDictKeys.toList.foreach { key =>
      infoDict.put(key, 0.0)
    }

  private def incrementFeesPaid(matches: Seq[Match]): Unit =
    Seq(
      (buyMatchFilter, InfoDictKeys.buyFeesPaid),
      (sellMatchFilter, InfoDictKeys.sellFeesPaid)
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
      (buyOrderFilter, InfoDictKeys.buyOrdersPlaced),
      (sellOrderFilter, InfoDictKeys.sellOrdersPlaced)
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

    infoDict.increment(InfoDictKeys.numOrdersRejected, numOrdersRejected)
  }

  private def incrementVolumeTraded(matches: Seq[Match]): Unit =
    Seq(
      (buyMatchFilter, InfoDictKeys.buyVolumeTraded),
      (sellMatchFilter, InfoDictKeys.sellVolumeTraded)
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

    infoDict.put(InfoDictKeys.portfolioValue, portfolioValue)
  }

  def getInfoDict: HashMap[String, Double] = infoDict

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
    (InfoDictKeys.toList.forall(key => infoDict.contains(key))
      && infoDict.values.forall(_ == 0.0))
}

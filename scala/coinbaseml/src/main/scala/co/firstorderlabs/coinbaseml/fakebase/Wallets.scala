package co.firstorderlabs.coinbaseml.fakebase

import java.math.BigDecimal
import java.util.UUID

import co.firstorderlabs.common.currency.Configs.ProductPrice.ProductVolume
import co.firstorderlabs.common.currency.Price.BtcUsdPrice.QuoteVolume
import co.firstorderlabs.common.currency.Volume.{Volume, VolumeCompanion}
import co.firstorderlabs.common.currency.{Constants => CurrencyConstants}
import co.firstorderlabs.common.protos.events.{
  BuyLimitOrder,
  BuyMarketOrder,
  Liquidity
}
import co.firstorderlabs.common.protos.fakebase.{
  Currency,
  WalletProto,
  Wallets => WalletsProto
}
import co.firstorderlabs.common.types.Events.{SellOrderEvent, _}

import scala.collection.mutable.HashMap

final case class Wallet[A <: Volume[A]](
    id: String,
    volume: VolumeCompanion[A],
    var balance: A,
    var holds: A
) {
  override def clone(): Wallet[A] = Wallet(id, volume, balance, holds)

  def setHolds(volume: A): Unit = {
    holds = volume
  }

  def toProto: WalletProto = {
    new WalletProto(
      id,
      volume.currency,
      balance.toPlainString,
      holds.toPlainString
    )
  }

  override def toString: String = {
    s"Wallet(${id}, ${volume.currency}, ${balance}, ${holds})"
  }
}

final case class WalletsSnapshot(
    walletsMap: Map[Currency, Wallets.GenericWallet]
) extends Snapshot

object Wallets extends Snapshotable[WalletsSnapshot] {
  type GenericWallet = Wallet[_ >: ProductVolume with QuoteVolume <: Volume[
    _ >: ProductVolume with QuoteVolume
  ]]
  type WalletMap = HashMap[Currency, GenericWallet]
  private val walletsMap: WalletMap = new HashMap

  def addFunds[A <: Volume[A]](volume: A): Unit = {
    getWallet(volume.companion).balance += volume
  }

  def calcRequiredBuyHold(order: BuyOrderEvent): QuoteVolume = {
    order match {
      case order: BuyLimitOrder => {
        val feeFraction =
          if (Exchange.checkIsTaker(order))
            CurrencyConstants.feeFraction(Liquidity.taker)
          else CurrencyConstants.feeFraction(Liquidity.maker)
        order.price * order.size * Left(feeFraction.add(new BigDecimal("1.0")))
      }
      case order: BuyMarketOrder => {
        val feeFraction = CurrencyConstants.feeFraction(Liquidity.taker)
        order.funds * Left(feeFraction.add(new BigDecimal("1.0")))
      }
    }
  }

  def createSnapshot: WalletsSnapshot = {
    val walletMap: Map[Currency, GenericWallet] = Map(
      ProductVolume.currency -> getWallet(ProductVolume).clone,
      QuoteVolume.currency -> getWallet(QuoteVolume).clone
    )
    WalletsSnapshot(walletMap)
  }

  def clear: Unit = walletsMap.clear

  def getAvailableFunds[A <: Volume[A]](
      volume: VolumeCompanion[A]
  ): Volume[A] = {
    val wallet = getWallet(volume)
    wallet.balance - wallet.holds
  }

  def incrementHolds(order: OrderEvent): Unit = {
    order match {
      case order: BuyOrderEvent => {
        val wallet = getWallet(QuoteVolume)
        val orderHolds = Wallets.calcRequiredBuyHold(order)
        val newHolds = wallet.holds + orderHolds
        wallet.setHolds(newHolds)
        order.holds = orderHolds
      }
      case order: SellOrderEvent => {
        val wallet = getWallet(ProductVolume)
        val newHolds = wallet.holds + order.size
        wallet.setHolds(newHolds)
        order.holds = order.size
      }
    }
  }

  def initializeWallets: Unit = {
    walletsMap(ProductVolume.currency) = Wallet(
      UUID.randomUUID().toString,
      ProductVolume,
      new ProductVolume(Right("0.0")),
      new ProductVolume(Right("0.0"))
    )
    walletsMap(QuoteVolume.currency) = Wallet(
      UUID.randomUUID().toString,
      QuoteVolume,
      new QuoteVolume(Right("0.0")),
      new QuoteVolume(Right("0.0"))
    )
  }

  def isCleared: Boolean = {
    walletsMap.isEmpty
  }

  def updateBalances(matchEvent: MatchEvent): Unit = {
    val productWallet = getWallet(ProductVolume)
    val quoteWallet = getWallet(QuoteVolume)
    matchEvent.getAccountOrder.get match {
      case order: BuyOrderEvent => {
        productWallet.balance = productWallet.balance + matchEvent.size
        val balanceDelta = matchEvent.quoteVolume + matchEvent.fee
        quoteWallet.balance = quoteWallet.balance - balanceDelta
        quoteWallet.holds = quoteWallet.holds - balanceDelta
        order.holds = order.holds - balanceDelta
      }
      case order: SellOrderEvent => {
        productWallet.balance = productWallet.balance - matchEvent.size
        productWallet.holds = productWallet.holds - matchEvent.size
        order.holds = order.holds - matchEvent.size
        quoteWallet.balance =
          quoteWallet.balance + matchEvent.quoteVolume - matchEvent.fee
      }
    }
  }

  def getWallet[A <: Volume[A]](volume: VolumeCompanion[A]): Wallet[A] = {
    walletsMap(volume.currency).asInstanceOf[Wallet[A]]
  }

  def removeHolds(order: OrderEvent): Unit = {
    order match {
      case order: BuyOrderEvent => {
        val wallet = getWallet(QuoteVolume)
        val newHolds = wallet.holds - order.holds
        wallet.setHolds(newHolds)
        order.holds = QuoteVolume.zeroVolume
      }
      case order: SellOrderEvent => {
        val wallet = getWallet(ProductVolume)
        val newHolds = wallet.holds - order.holds
        wallet.setHolds(newHolds)
        order.holds = ProductVolume.zeroVolume
      }
    }
  }

  def restore(snapshot: WalletsSnapshot): Unit = {
    clear
    snapshot.walletsMap.foreach(item => walletsMap.put(item._1, item._2.clone))
  }

  def toProto: WalletsProto =
    WalletsProto(
      Map(
        ProductVolume.currency.name -> getWallet(ProductVolume).toProto,
        QuoteVolume.currency.name -> getWallet(QuoteVolume).toProto
      )
    )

}

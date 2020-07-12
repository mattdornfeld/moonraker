package co.firstorderlabs.fakebase

import java.math.BigDecimal
import java.util.UUID

import co.firstorderlabs.fakebase.Wallets.WalletMap
import co.firstorderlabs.fakebase.currency.Configs.ProductPrice.{ProductVolume, QuoteVolume}
import co.firstorderlabs.fakebase.currency.Volume.{Volume, VolumeCompanion}
import co.firstorderlabs.fakebase.protos.fakebase.{Wallets => WalletsProto, _}
import co.firstorderlabs.fakebase.types.Events.{SellOrderEvent, _}

import scala.collection.mutable.HashMap

case class Wallet[A <: Volume[A]](id: String,
                                  currency: Currency,
                                  initialBalance: A,
                                  initialHolds: A) {
  var balance = initialBalance
  var holds = initialHolds

  def setHolds(volume: A): Unit = {
    holds = volume
  }

  def toProto: WalletProto = {
    new WalletProto(id, currency, balance.toPlainString, holds.toPlainString)
  }

  override def toString: String = {
    s"Wallet(${id}, ${currency}, ${balance}, ${holds})"
  }
}

case class WalletsCheckpoint(walletsMap: WalletMap) extends Checkpoint

object Wallets extends Checkpointable[WalletsCheckpoint] {
  type GenericWallet = Wallet[_ >: ProductVolume with QuoteVolume <: Volume[_ >: ProductVolume with QuoteVolume]]
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
            Configs.feeFraction(Liquidity.taker)
          else Configs.feeFraction(Liquidity.maker)
        order.price * order.size * Left(feeFraction.add(new BigDecimal("1.0")))
      }
      case order: BuyMarketOrder => {
        val feeFraction = Configs.feeFraction(Liquidity.taker)
        order.funds * Left(feeFraction.add(new BigDecimal("1.0")))
      }
    }
  }

  def checkpoint: WalletsCheckpoint = WalletsCheckpoint(walletsMap.clone)

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
      ProductVolume.currency,
      new ProductVolume(Right("0.0")),
      new ProductVolume(Right("0.0"))
    )
    walletsMap(QuoteVolume.currency) = Wallet(
      UUID.randomUUID().toString,
      QuoteVolume.currency,
      new QuoteVolume(Right("0.0")),
      new QuoteVolume(Right("0.0")),
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
        quoteWallet.balance = quoteWallet.balance + matchEvent.quoteVolume - matchEvent.fee
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

  def restore(checkpoint: WalletsCheckpoint): Unit = {
    clear
    walletsMap.addAll(checkpoint.walletsMap.iterator)
  }

  def toProto: WalletsProto =
    WalletsProto(
      Map(
        ProductVolume.currency.name -> getWallet(ProductVolume).toProto,
        QuoteVolume.currency.name -> getWallet(QuoteVolume).toProto
      )
    )

}
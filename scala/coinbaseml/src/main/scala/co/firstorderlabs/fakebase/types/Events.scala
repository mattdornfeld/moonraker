package co.firstorderlabs.fakebase.types

import co.firstorderlabs.fakebase.Configs
import co.firstorderlabs.fakebase.protos.fakebase.{Liquidity, MatchEvents, Order, OrderSide, OrderStatus}
import co.firstorderlabs.fakebase.currency.Configs.ProductPrice
import co.firstorderlabs.fakebase.currency.Configs.ProductPrice.{ProductVolume, QuoteVolume}
import co.firstorderlabs.fakebase.types.Types.{Datetime, OrderId, OrderRequestId, ProductId}

object Events {
  trait SpecifiesFunds {
    val funds: ProductPrice.QuoteVolume
    private var _remainingFunds: Option[ProductPrice.QuoteVolume] = None

    def copySpecifiesFundsVars(that: SpecifiesFunds): Unit = {
      setRemainingFunds(that.remainingFunds)
    }

    def remainingFunds: ProductPrice.QuoteVolume = {
      if (_remainingFunds.isEmpty) _remainingFunds = Some(funds)
      _remainingFunds.get
    }

    def setRemainingFunds(remainingFunds: ProductPrice.QuoteVolume) = {
      _remainingFunds = Some(remainingFunds)
    }
  }

  trait SpecifiesSize {
    val size: ProductPrice.ProductVolume
    private var _remainingSize: Option[ProductPrice.ProductVolume] = None

    def copySpecifiesSizeVars(that: SpecifiesSize): Unit = {
      setRemainingSize(that.remainingSize)
    }

    def remainingSize: ProductPrice.ProductVolume = {
      if (_remainingSize.isEmpty) _remainingSize = Some(size)
      _remainingSize.get
    }

    def setRemainingSize(remainingSize: ProductPrice.ProductVolume) = {
      _remainingSize = Some(remainingSize)
    }
  }

  trait OrderRequest

  trait LimitOrderRequst extends OrderRequest {
    val postOnly: Boolean
  }

  trait Event {
    val productId: ProductId
    val time: Datetime

    def equalTo[A](that: A): Boolean = this == that
  }

  trait MatchEvent extends Event {
    val liquidity: Liquidity
    val price: ProductPrice
    val makerOrder: Order
    val takerOrder: Order
    val size: ProductVolume

    def fee: QuoteVolume = {
      val feeFraction = Configs.feeFraction(liquidity)
      quoteVolume * Right(feeFraction.toString.toDouble)
    }

    def getAccountOrder: Option[OrderEvent] = {
      liquidity match {
        case Liquidity.maker => getMakerOrder
        case Liquidity.taker => getTakerOrder
        case Liquidity.global => None
      }
    }

    def quoteVolume: QuoteVolume = {
      price * size
    }

    private def getMakerOrder: Option[OrderEvent] = {
      if (makerOrder.asMessage.sealedValue.buyLimitOrder.isDefined) {
        makerOrder.asMessage.sealedValue.buyLimitOrder
      } else if (makerOrder.asMessage.sealedValue.sellLimitOrder.isDefined) {
        makerOrder.asMessage.sealedValue.sellLimitOrder
      } else{
        None
      }
    }

    private def getTakerOrder: Option[OrderEvent] = {
      if (takerOrder.asMessage.sealedValue.buyLimitOrder.isDefined) {
        takerOrder.asMessage.sealedValue.buyLimitOrder
      } else if (takerOrder.asMessage.sealedValue.sellLimitOrder.isDefined) {
        takerOrder.asMessage.sealedValue.sellLimitOrder
      } else if (takerOrder.asMessage.sealedValue.buyMarketOrder.isDefined) {
        takerOrder.asMessage.sealedValue.buyMarketOrder
      } else if (takerOrder.asMessage.sealedValue.sellMarketOrder.isDefined) {
        takerOrder.asMessage.sealedValue.sellMarketOrder
      } else{
        None
      }
    }
  }

  trait OrderEvent extends Event {
    val orderId: OrderId
    val orderStatus: OrderStatus
    val requestId: OrderRequestId
    val side: OrderSide
    val matchEvents: Option[MatchEvents]
  }

  trait BuyOrderEvent extends OrderEvent {
    var holds: QuoteVolume = QuoteVolume.zeroVolume

    def copyBuyOrderEventVars(that: BuyOrderEvent): Unit = {
      holds = that.holds
    }
  }

  trait SellOrderEvent extends OrderEvent with SpecifiesSize {
    var holds: ProductVolume = ProductVolume.zeroVolume

    def copySellOrderEventVars(that: SellOrderEvent): Unit = {
      holds = that.holds
    }

  }

  trait LimitOrderEvent extends OrderEvent with SpecifiesSize {
    val price: ProductPrice
    var degeneracy = 0

    def copyLimitOrderEventVars(that: LimitOrderEvent): Unit = {
      degeneracy = that.degeneracy
    }

    def incrementDegeneracy: Unit = {
      degeneracy += 1
    }
  }
  trait MarketOrderEvent extends OrderEvent
}

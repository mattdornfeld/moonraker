package co.firstorderlabs.fakebase.types

import java.time.{Duration, Instant}

import co.firstorderlabs.fakebase.{Constants, Exchange, OrderBookKey}
import co.firstorderlabs.fakebase.currency.Configs.ProductPrice
import co.firstorderlabs.fakebase.currency.Configs.ProductPrice.{
  ProductVolume,
  QuoteVolume
}
import co.firstorderlabs.fakebase.protos.fakebase._
import co.firstorderlabs.fakebase.types.Types.{
  OrderId,
  OrderRequestId,
  ProductId
}

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

    def setRemainingSize(remainingSize: ProductPrice.ProductVolume): Unit = {
      _remainingSize = Some(remainingSize)
    }
  }

  trait SpecifiesPrice {
    val price: ProductPrice
  }

  trait OrderRequest

  trait BuyOrderRequest extends OrderRequest

  trait SellOrderRequest extends OrderRequest

  trait LimitOrderRequest extends OrderRequest {
    val postOnly: Boolean
  }

  trait MarketOrderRequest extends OrderRequest

  trait Event {
    val productId: ProductId
    val time: Instant

    def equalTo[A](that: A): Boolean = this == that
  }

  trait MatchEvent extends Event with SpecifiesPrice with SpecifiesSize {
    val liquidity: Liquidity
    val makerOrder: Order
    val takerOrder: Order
    val size: ProductVolume

    def fee: QuoteVolume = {
      val feeFraction = Constants.feeFraction(liquidity)
      quoteVolume * Right(feeFraction.toString.toDouble)
    }

    def getAccountOrder: Option[OrderEvent] = {
      liquidity match {
        case Liquidity.maker  => getMakerOrder
        case Liquidity.taker  => getTakerOrder
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
      } else {
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
      } else {
        None
      }
    }
  }

  trait OrderEvent extends Event {
    val doneAt: Instant
    val doneReason: DoneReason
    val orderId: OrderId
    val orderStatus: OrderStatus
    val rejectReason: RejectReason
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

  trait LimitOrderEvent
      extends OrderEvent
      with SpecifiesPrice
      with SpecifiesSize {
    val timeToLive: Option[Duration]
    var degeneracy = 0

    def copyLimitOrderEventVars(that: LimitOrderEvent): Unit = {
      degeneracy = that.degeneracy
    }

    def getOrderBookKey: OrderBookKey =
      this.side match {
        case OrderSide.buy =>
          OrderBookKey(
            this.price,
            Duration.between(Instant.MAX, this.time),
            this.degeneracy
          )
        case OrderSide.sell =>
          OrderBookKey(
            this.price,
            Duration.between(this.time, Instant.MIN),
            this.degeneracy
          )
      }

    def incrementDegeneracy: Unit = {
      degeneracy += 1
    }

    def isExpired: Boolean = {
      val timeOfExpiration = time
        .plus(timeToLive.getOrElse(Duration.ZERO))

      Exchange.getSimulationMetadata.currentTimeInterval.startTime
        .plus(Exchange.getSimulationMetadata.timeDelta)
        .compareTo(timeOfExpiration) >= 0
    }
  }

  trait MarketOrderEvent extends OrderEvent
}

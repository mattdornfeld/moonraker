package co.firstorderlabs.common.types

import java.time.{Duration, Instant}
import java.util.{Map => JavaMap}

import co.firstorderlabs.common.currency.Configs.ProductPrice
import co.firstorderlabs.common.currency.Configs.ProductPrice.{
  ProductVolume,
  QuoteVolume
}
import co.firstorderlabs.common.currency.Constants
import co.firstorderlabs.common.protos.events.{
  BuyLimitOrder,
  BuyMarketOrder,
  DoneReason,
  Liquidity,
  Match,
  MatchEvents,
  Order,
  OrderSide,
  OrderStatus,
  RejectReason,
  SellLimitOrder,
  SellMarketOrder,
  Cancellation => CancellationProto,
  Event => EventProto
}
import co.firstorderlabs.common.protos.fakebase.OrderType
import co.firstorderlabs.common.types.Types.{
  OrderId,
  OrderRequestId,
  ProductId,
  SimulationId,
  TimeInterval,
  TradeId
}

import scala.jdk.CollectionConverters._

object Events {
  trait OrderRequest {
    val simulationId: Option[SimulationId]
  }

  trait BuyOrderRequest extends OrderRequest

  trait SellOrderRequest extends OrderRequest

  trait LimitOrderRequest extends OrderRequest {
    val postOnly: Boolean
  }

  trait MarketOrderRequest extends OrderRequest

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

    def setRemainingFunds(remainingFunds: ProductPrice.QuoteVolume): Unit = {
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

  trait Event {
    val productId: ProductId
    val time: Instant

    def equalTo[A](that: A): Boolean = this == that

    def toBigQueryRow: JavaMap[String, Any]

    def toEventProto: EventProto = {
      this match {
        case event: CancellationProto => EventProto().withCancellation(event)
        case event: BuyLimitOrder     => EventProto().withBuyLimitOrder(event)
        case event: BuyMarketOrder    => EventProto().withBuyMarketOrder(event)
        case event: Match             => EventProto().withMatch(event)
        case event: SellLimitOrder    => EventProto().withSellLimitOrder(event)
        case event: SellMarketOrder   => EventProto().withSellMarketOrder(event)
      }
    }
  }

  trait CancellationEvent extends Event with SpecifiesPrice {
    val orderId: OrderId
    val price: ProductPrice
    val productId: ProductId
    val side: OrderSide
    val remainingSize: ProductVolume
    val time: Instant

    override def toBigQueryRow: JavaMap[String, Any] =
      Map(
        "order_id" -> orderId.orderId,
        "price" -> price.toPlainString,
        "product_id" -> productId.toString,
        "side" -> side.name,
        "remaining_size" -> remainingSize.toPlainString,
        "time" -> time.toString
      ).asInstanceOf[Map[String, Any]].asJava
  }

  trait MatchEvent extends Event with SpecifiesPrice with SpecifiesSize {
    val liquidity: Liquidity
    val makerOrder: Order
    val makerOrderId: OrderId
    val takerOrder: Order
    val side: OrderSide
    val size: ProductVolume
    val takerOrderId: OrderId
    val tradeId: TradeId

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

    override def toBigQueryRow: JavaMap[String, Any] = {
      Map(
        "maker_order_id" -> makerOrderId.orderId,
        "price" -> price.toPlainString,
        "product_id" -> productId.toString,
        "side" -> side.name,
        "size" -> size.toPlainString,
        "taker_order_id" -> takerOrderId.orderId,
        "time" -> time.toString,
        "trade_id" -> tradeId.tradeId
      ).asJava
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

  final case class OrderBookKey(
      price: ProductPrice,
      time: Duration,
      degeneracy: Int
  ) {
    private val _hashCode =
      scala.runtime.Statics.anyHash((price, time, degeneracy))

    override def hashCode(): Int = _hashCode
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

    def orderBookKey: OrderBookKey =
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

    def isExpired(currentTimeInterval: TimeInterval): Boolean = {
      val timeOfExpiration = time
        .plus(timeToLive.getOrElse(Duration.ZERO))

      currentTimeInterval.startTime
        .plus(currentTimeInterval.size)
        .compareTo(timeOfExpiration) >= 0
    }

    def toCancellation: CancellationProto =
      CancellationProto(
        orderId = orderId,
        productId = productId,
        side = side
      )

    override def toBigQueryRow: JavaMap[String, Any] =
      Map(
        "order_id" -> orderId.orderId,
        "order_status" -> orderStatus.name,
        "order_type" -> OrderType.limit.name,
        "price" -> price.toPlainString,
        "product_id" -> productId.toString,
        "side" -> side.name,
        "size" -> size.toPlainString,
        "time" -> time.toString
      ).asInstanceOf[Map[String, Any]].asJava
  }

  trait MarketOrderEvent extends OrderEvent

  trait BuyMarketOrderEvent extends MarketOrderEvent with BuyOrderEvent {
    val funds: QuoteVolume

    override def toBigQueryRow: JavaMap[String, Any] =
      Map(
        "funds" -> funds.toPlainString,
        "order_id" -> orderId.orderId,
        "order_status" -> orderStatus.name,
        "order_type" -> OrderType.market.name,
        "product_id" -> productId.toString,
        "side" -> side.name,
        "time" -> time.toString
      ).asInstanceOf[Map[String, Any]].asJava
  }

  trait SellMarketOrderEvent extends MarketOrderEvent with SellOrderEvent {
    val size: ProductVolume

    override def toBigQueryRow: JavaMap[String, Any] =
      Map(
        "order_id" -> orderId.orderId,
        "order_status" -> orderStatus.name,
        "order_type" -> OrderType.market.name,
        "product_id" -> productId.toString,
        "side" -> side.name,
        "size" -> size.toPlainString,
        "time" -> time.toString
      ).asInstanceOf[Map[String, Any]].asJava
  }
}

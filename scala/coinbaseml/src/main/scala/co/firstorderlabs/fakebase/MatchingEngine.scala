package co.firstorderlabs.fakebase

import java.math.{BigDecimal, RoundingMode}
import java.util.UUID

import co.firstorderlabs.fakebase.Account.Account
import co.firstorderlabs.fakebase.currency.Configs.ProductPrice
import co.firstorderlabs.fakebase.currency.Configs.ProductPrice.{ProductVolume, QuoteVolume}
import co.firstorderlabs.fakebase.protos.fakebase._
import co.firstorderlabs.fakebase.types.Events._
import co.firstorderlabs.fakebase.types.Exceptions.SelfTrade
import co.firstorderlabs.fakebase.types.Types._

import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer

case class MatchingEngineCheckpoint(buyOrderBookCheckpoint: OrderBookCheckpoint,
                                    matches: ListBuffer[Match],
                                    sellOrderBookCheckpoint: OrderBookCheckpoint) extends Checkpoint

object MatchingEngine extends Checkpointable[MatchingEngineCheckpoint] {
  val matches = new ListBuffer[Match]
  val orderBooks = Map[OrderSide, OrderBook](OrderSide.buy -> new OrderBook, OrderSide.sell -> new OrderBook)

  def cancelOrder(order: OrderEvent): OrderEvent = {
    require(List(OrderStatus.open, OrderStatus.received).contains(order.orderStatus), "can only cancel open or received orders")

    if (order.orderStatus.isopen) orderBooks(order.side).removeByOrderId(order.orderId)

    if (Account.belongsToAccount(order))
      Account.closeOrder(order, DoneReason.cancelled).get
    else
      OrderUtils.setOrderStatusToDone(order, DoneReason.cancelled)
  }

  def checkpoint: MatchingEngineCheckpoint = MatchingEngineCheckpoint(
    orderBooks(OrderSide.buy).checkpoint,
    matches.clone,
    orderBooks(OrderSide.sell).checkpoint
  )

  def checkIsTaker(order: LimitOrderEvent): Boolean = {
    order match {
      case order: BuyOrderEvent => {
        val minSellPrice = orderBooks(OrderSide.sell).minPrice
          .getOrElse(ProductPrice.maxPrice)

        order.price >= minSellPrice
      }
      case order: SellOrderEvent => {
        val maxBuyPrice = orderBooks(OrderSide.buy).maxPrice
          .getOrElse(ProductPrice.zeroPrice)

        order.price <= maxBuyPrice
      }
    }
  }

  def clear: Unit = {
    orderBooks(OrderSide.buy).clear
    orderBooks(OrderSide.sell).clear
    matches.clear
  }

  def processEvents(events: List[Event]): Unit = {
    events.foreach(
      event => {
        event match {
          case cancellation: Cancellation => processCancellation(cancellation)
          case order: OrderEvent               => processOrder(order)
      }}
    )
  }

  def restore(checkpoint: MatchingEngineCheckpoint): Unit = {
    clear
    orderBooks(OrderSide.buy).restore(checkpoint.buyOrderBookCheckpoint)
    orderBooks(OrderSide.sell).restore(checkpoint.sellOrderBookCheckpoint)
    matches.addAll(checkpoint.matches.iterator)
  }

  @tailrec
  private def addToOrderBook(order: LimitOrderEvent): Unit = {
    if (orderBooks(order.side).getOrderByOrderId(order.orderId).isEmpty) {
      val orderBookKey = OrderBook.getOrderBookKey(order)

      if (orderBooks(order.side).getOrderByOrderBookKey(orderBookKey).isEmpty) {
        val updatedOrder = if (Account.belongsToAccount(order))
          Account.openOrder(order.orderId).get
        else
          OrderUtils.openOrder(order)

       orderBooks(order.side).update(orderBookKey, updatedOrder)

      } else {
        order.incrementDegeneracy
        addToOrderBook(order)
      }
    }
  }

  private def checkForSelfTrade(makerOrder: LimitOrderEvent, takerOrder: OrderEvent): Boolean = {
    List(makerOrder, takerOrder)
      .map(Account.belongsToAccount)
      .forall(_ == true)
  }

  private def getLiquidity(makerOrder: LimitOrderEvent, takerOrder: OrderEvent): Liquidity = {
    (Account.belongsToAccount(makerOrder), Account.belongsToAccount(takerOrder)) match {
      case (true, false) => Liquidity.maker
      case (false, true) => Liquidity.taker
      case (false, false) => Liquidity.global
      case (true, true) => throw new SelfTrade
    }
  }

  private def processMatchedMakerOrder(makerOrder: LimitOrderEvent, liquidity: Liquidity): LimitOrderEvent = {
    if (makerOrder.remainingSize.isZero) {
      if (liquidity.ismaker) {
        Account.closeOrder(makerOrder, DoneReason.filled).get
      } else {
        OrderUtils.setOrderStatusToDone(makerOrder, DoneReason.filled)
      }
    }
    else {
      makerOrder
    }
  }

  private def processMatchedTakerOrder(takerOrder: OrderEvent, liquidity: Liquidity): OrderEvent = {
    val isFilled = takerOrder match {
      case takerOrder: SpecifiesSize => takerOrder.remainingSize.isZero
      case takerOrder: SpecifiesFunds => takerOrder.remainingFunds.isZero
      }

    if (isFilled) {
      if (liquidity.istaker) {
        Account.closeOrder(takerOrder, DoneReason.filled).get
      } else {
        OrderUtils.setOrderStatusToDone(takerOrder, DoneReason.filled)
      }
    } else {
      takerOrder
    }
  }

  private def createMatch(filledVolume: ProductVolume,
                          makerOrder: LimitOrderEvent,
                          takerOrder: OrderEvent) = {
    val liquidity = getLiquidity(makerOrder, takerOrder)
    val updatedMakerOrder = processMatchedMakerOrder(makerOrder, liquidity)
    val updatedTakerOrder = processMatchedTakerOrder(takerOrder, liquidity)

    val matchEvent = Match(
      liquidity,
      makerOrder.orderId,
      makerOrder.price,
      makerOrder.productId,
      makerOrder.side,
      filledVolume,
      takerOrder.orderId,
      takerOrder.time,
      TradeId(UUID.randomUUID.hashCode),
      OrderUtils.getOrderSealedValue(updatedMakerOrder),
      OrderUtils.getOrderSealedValue(updatedTakerOrder)
    )

    matches += matchEvent

    if (!liquidity.isglobal) {
      Account.processMatch(matchEvent)
    }
  }

  private def deincrementRemainingSizes(makerOrder: LimitOrderEvent, takerOrder: SpecifiesSize): ProductVolume = {
    val filledVolume =
      List(makerOrder.remainingSize, takerOrder.remainingSize).min
    val makerOrderRemainingSize = List(
      ProductVolume.zeroVolume,
      makerOrder.remainingSize - takerOrder.remainingSize
    ).max
    val takerOrderRemainingSize = List(
      ProductVolume.zeroVolume,
      takerOrder.remainingSize - makerOrder.remainingSize
    ).max

    makerOrder.setRemainingSize(makerOrderRemainingSize)
    takerOrder.setRemainingSize(takerOrderRemainingSize)

    filledVolume
  }

  /**Called when a BuyMarketOrder matches with a sell side LimitOrder. Will deincrement takerOrder.remainingFunds
    * and makerOrder.remainingSize. Returns the filled ProductVolume
    *
    * @param makerOrder
    * @param takerOrder
    * @return
    */
  private def deincrementRemainingSizeAndFunds(
                                                makerOrder: LimitOrderEvent,
                                                takerOrder: BuyMarketOrder
  ): ProductVolume = {
    val takerOrderDesiredVolume = new ProductVolume(
      Left(takerOrder.funds.amount.divide(makerOrder.price.amount, ProductVolume.mathContext))
    )
    val filledVolume =
      List(takerOrderDesiredVolume, makerOrder.remainingSize).min

    val takerOrderRemainingFunds = List(
      QuoteVolume.zeroVolume,
      takerOrder.remainingFunds - makerOrder.price * filledVolume
    ).max

    val makerOrderRemainingSize = List(
      ProductVolume.zeroVolume,
      makerOrder.remainingSize - filledVolume
    ).max

    takerOrder.setRemainingFunds(takerOrderRemainingFunds)
    makerOrder.setRemainingSize(makerOrderRemainingSize)

    filledVolume
  }

  private def getBestMakerOrder(order: OrderEvent): Option[LimitOrderEvent] = {
    order match {
      case _: BuyOrderEvent  => orderBooks(OrderSide.sell).minOrder
      case _: SellOrderEvent => orderBooks(OrderSide.buy).maxOrder
    }
  }

  @tailrec
  private def processBuyMarketOrder(order: BuyMarketOrder): Unit = {
    if (order.remainingFunds > QuoteVolume.zeroVolume) {
      val makerOrder = getBestMakerOrder(order)

      makerOrder match {
        case None => {
          cancelOrder(order)
        }
        case Some(makerOrder) => {
          val shouldCancel = checkForSelfTrade(makerOrder, order) || SlippageProtection
            .checkSlippageGreaterThanMax(makerOrder.price)

          if (shouldCancel) {
            cancelOrder(order)
            return
          }

          val filledVolume = deincrementRemainingSizeAndFunds(makerOrder, order)

          createMatch(filledVolume, makerOrder, order)

          if (makerOrder.remainingSize.isZero()) {
            orderBooks(makerOrder.side).removeByOrderId(makerOrder.orderId)
          }

          processBuyMarketOrder(order)
        }
      }
    } else {
      SlippageProtection.reset
    }
  }

  @tailrec
  private def processSellMarketOrder(order: SellMarketOrder): Unit = {
    if (order.remainingSize > ProductVolume.zeroVolume) {
      val makerOrder = getBestMakerOrder(order)

      makerOrder match {
        case None => {
          cancelOrder(order)
        }
        case Some(makerOrder) => {
          val shouldCancel = checkForSelfTrade(makerOrder, order) || SlippageProtection
            .checkSlippageGreaterThanMax(makerOrder.price)

          if (shouldCancel) {
            cancelOrder(order)
            return
          }

          val filledVolume = deincrementRemainingSizes(makerOrder, order)

          createMatch(filledVolume, makerOrder, order)

          if (makerOrder.remainingSize.isZero()) {
            orderBooks(makerOrder.side).removeByOrderId(makerOrder.orderId)
          }

          processSellMarketOrder(order)
        }
      }
    } else {
      SlippageProtection.reset
    }
  }

  private def processCancellation(cancellation: Cancellation) = {
    orderBooks(cancellation.side)
      .getOrderByOrderId(cancellation.orderId)
      .collect{order => cancelOrder(order)}
  }

  private def processMarketOrder(order: MarketOrderEvent) = {
    order match {
      case order: BuyMarketOrder  => processBuyMarketOrder(order)
      case order: SellMarketOrder => processSellMarketOrder(order)
    }

  }

  /**Process LimitOrder logic. Will do the following:
    * 1. Check if order is taker. If not will add order to order book and return.
    * 2. If order.remainingSize == 0 will consider processing to be done and return.
    * 3. If order is taker and order.remainingSize <= 0 will attempt to get best matching maker order from order book.
    *    If order book is empty will add order to order book and return.
    * 4. If above conditions are not met will check if attempting to match with order from self or if max slippage has been surpassed.
    *    If these conditions are met will cancel order and return.
    * 5. If above conditions are not met will match maker and taker order and create Match event.
    * 6. If maker.remainingSize == 0 will remove maker order from order book.
    * 7. Will recursively call processLimitOrder until one of the above exist conditions is met.
    *
    * @param order
    */
  @tailrec
  private def processLimitOrder(order: LimitOrderEvent): Unit = {
    if (!checkIsTaker(order)) {
      addToOrderBook(order)
      return
    }

    if (order.remainingSize > ProductVolume.zeroVolume) {
      val makerOrder = getBestMakerOrder(order)

      makerOrder match {
        case None => {
          addToOrderBook(order)
        }
        case Some(makerOrder) => {
          val shouldCancel = checkForSelfTrade(makerOrder, order) || SlippageProtection
            .checkSlippageGreaterThanMax(makerOrder.price)

          if (shouldCancel) {
            cancelOrder(order)
            return
          }

          val filledVolume = deincrementRemainingSizes(makerOrder, order)

          createMatch(filledVolume, makerOrder, order)

          if (makerOrder.remainingSize.isZero()) {
            orderBooks(makerOrder.side).removeByOrderId(makerOrder.orderId)
          }

          processLimitOrder(order)
        }
      }
    } else {
      SlippageProtection.reset()
    }
  }

  private def processOrder(order: OrderEvent): Unit = {
    order match {
      case order: LimitOrderEvent  => processLimitOrder(order)
      case order: MarketOrderEvent => processMarketOrder(order)
    }

  }
}

object SlippageProtection {
  private val maxPriceSlippagePoints = new BigDecimal("0.1")
  private var firstMatchPrice: Option[ProductPrice] = None
  private var priceSlippagePoints = new BigDecimal("0.0")

  def checkSlippageGreaterThanMax(makerOrderPrice: ProductPrice) = {
    firstMatchPrice match {
      case None => firstMatchPrice = Some(makerOrderPrice)
      case Some(firstMatchPrice) => {
        priceSlippagePoints = (
          (makerOrderPrice.amount
            .subtract(firstMatchPrice.amount))
            .divide(firstMatchPrice.amount, 6, RoundingMode.HALF_UP)
            .abs
        )
      }
    }

    priceSlippagePoints.compareTo(SlippageProtection.maxPriceSlippagePoints) >= 0
  }

  def reset() = {
    firstMatchPrice = None
    priceSlippagePoints = new BigDecimal("0.0")
  }
}
package co.firstorderlabs.coinbaseml.fakebase

import java.math.{BigDecimal, RoundingMode}
import java.util.UUID
import java.util.logging.Logger

import co.firstorderlabs.coinbaseml.fakebase.Types.Exceptions.SelfTrade
import co.firstorderlabs.coinbaseml.fakebase.utils.OrderUtils
import co.firstorderlabs.common.currency.Configs.ProductPrice
import co.firstorderlabs.common.currency.Configs.ProductPrice.{
  ProductVolume,
  QuoteVolume
}
import co.firstorderlabs.common.protos.events.{
  BuyLimitOrder,
  BuyMarketOrder,
  Cancellation,
  DoneReason,
  Liquidity,
  Match,
  OrderSide,
  OrderStatus,
  SellLimitOrder,
  SellMarketOrder
}
import co.firstorderlabs.common.types.Events._
import co.firstorderlabs.common.types.Types._

import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer

final case class MatchingEngineState(
    buyOrderBookState: BuyOrderBookState,
    matches: ListBuffer[Match],
    sellOrderBookState: SellOrderBookState,
    var currentPortfolioValue: Option[Double] = None,
    var previousPortfolioValue: Option[Double] = None,
    var checkpointPortfolioValue: Option[Double] = None
) extends State[MatchingEngineState] {
  override val companion = MatchingEngineState
  override def createSnapshot(implicit
      simulationState: SimulationState
  ): MatchingEngineState = {
    checkpointPortfolioValue = currentPortfolioValue
    MatchingEngineState(
      buyOrderBookState.createSnapshot,
      matches.clone,
      sellOrderBookState.createSnapshot,
      currentPortfolioValue,
      previousPortfolioValue
    )
  }

  def getOrderBookState(side: OrderSide): OrderBookState =
    if (side.isbuy) buyOrderBookState else sellOrderBookState

  def orderBookIsEmpty: Boolean =
    buyOrderBookState.orderIdLookup.isEmpty && buyOrderBookState.orderIdLookup.isEmpty

}

object MatchingEngineState extends StateCompanion[MatchingEngineState] {
  override def create(implicit
      simulationMetadata: SimulationMetadata
  ): MatchingEngineState =
    MatchingEngineState(
      BuyOrderBookState.create,
      new ListBuffer[Match],
      SellOrderBookState.create
    )

  override def fromSnapshot(
      snapshot: MatchingEngineState
  ): MatchingEngineState = {
    val matchingEngineState = MatchingEngineState(
      BuyOrderBookState.fromSnapshot(snapshot.buyOrderBookState),
      new ListBuffer[Match],
      SellOrderBookState.fromSnapshot(snapshot.sellOrderBookState),
      snapshot.currentPortfolioValue,
      snapshot.previousPortfolioValue,
      snapshot.checkpointPortfolioValue
    )
    matchingEngineState.matches.addAll(snapshot.matches.iterator)
    matchingEngineState
  }
}

object MatchingEngine {
  private val logger = Logger.getLogger(this.toString)

  def cancelOrder(order: OrderEvent)(implicit
      accountState: AccountState,
      orderBookState: OrderBookState,
      simulationMetadata: SimulationMetadata
  ): OrderEvent = {
    require(
      List(OrderStatus.open, OrderStatus.received).contains(order.orderStatus),
      "can only cancel open or received orders"
    )

    if (order.orderStatus.isopen) {
      OrderBook.removeByOrderId(order.orderId)
    }

    if (Account.belongsToAccount(order))
      Account.closeOrder(order, DoneReason.canceled)
    else
      OrderUtils.setOrderStatusToDone(order, DoneReason.canceled)
  }

  def checkIsTaker(
      order: LimitOrderEvent
  )(implicit matchingEngineState: MatchingEngineState): Boolean = {
    order match {
      case order: BuyOrderEvent => {
        val minSellPrice = OrderBook
          .minPrice(matchingEngineState.sellOrderBookState)
          .getOrElse(ProductPrice.maxPrice)

        order.price >= minSellPrice
      }
      case order: SellOrderEvent => {
        val maxBuyPrice = OrderBook
          .maxPrice(matchingEngineState.buyOrderBookState)
          .getOrElse(ProductPrice.zeroPrice)

        order.price <= maxBuyPrice
      }
    }
  }

  def processEvents(events: List[Event])(implicit
      accountState: AccountState,
      matchingEngineState: MatchingEngineState,
      simulationMetadata: SimulationMetadata
  ): Unit = {
    val _events = events.iterator
    while (_events.hasNext) {
      _events.next match {
        case cancellation: Cancellation if cancellation.side.isbuy =>
          processCancellation(cancellation)(
            accountState,
            matchingEngineState.buyOrderBookState,
            simulationMetadata
          )
        case cancellation: Cancellation if cancellation.side.issell =>
          processCancellation(cancellation)(
            accountState,
            matchingEngineState.sellOrderBookState,
            simulationMetadata
          )
        case order: OrderEvent => processOrder(order)
      }
    }
  }

  @tailrec
  private def addToOrderBook(order: LimitOrderEvent)(implicit
      accountState: AccountState,
      orderBookState: OrderBookState
  ): Unit = {
    if (OrderBook.getOrderByOrderId(order.orderId).isEmpty) {
      val orderBookKey = OrderBook.getOrderBookKey(order)

      if (OrderBook.getOrderByOrderBookKey(orderBookKey).isEmpty) {
        val updatedOrder =
          if (Account.belongsToAccount(order))
            Account.openOrder(order.orderId)
          else
            OrderUtils.openOrder(order)

        OrderBook.update(orderBookKey, updatedOrder)
      } else {
        order.incrementDegeneracy
        addToOrderBook(order)
      }
    }
  }

  private def checkForSelfTrade(
      makerOrder: LimitOrderEvent,
      takerOrder: OrderEvent
  )(implicit accountState: AccountState): Boolean = {
    List(makerOrder, takerOrder)
      .map(Account.belongsToAccount)
      .forall(_ == true)
  }

  private def getLiquidity(
      makerOrder: LimitOrderEvent,
      takerOrder: OrderEvent
  )(implicit accountState: AccountState): Liquidity = {
    (
      Account.belongsToAccount(makerOrder),
      Account.belongsToAccount(takerOrder)
    ) match {
      case (true, false)  => Liquidity.maker
      case (false, true)  => Liquidity.taker
      case (false, false) => Liquidity.global
      case (true, true)   => throw new SelfTrade
    }
  }

  def calcMidPrice(implicit
      matchingEngineState: MatchingEngineState
  ): Double = {
    val bestAskPrice =
      OrderBook.minPrice(matchingEngineState.sellOrderBookState).getOrElse {
        logger.warning(
          "The best ask price is 0. This indicates the sell order book is empty"
        )
        ProductPrice.zeroPrice
      }
    val bestBidPrice =
      OrderBook.maxPrice(matchingEngineState.buyOrderBookState).getOrElse {
        logger.warning(
          "The best bid price is 0. This indicates the buy order book is empty"
        )
        ProductPrice.zeroPrice
      }

    ((bestAskPrice + bestBidPrice) / Right(2.0)).toDouble
  }

  def calcPortfolioValue(implicit
      matchingEngineState: MatchingEngineState,
      simulationMetadata: SimulationMetadata,
      walletsState: WalletsState
  ): Double = {
    val (productWallet, quoteWallet) =
      (Wallets.getWallet(ProductVolume), Wallets.getWallet(QuoteVolume))

    val productVolume = productWallet.balance.toDouble
    val quoteVolume = quoteWallet.balance.toDouble
    val orderBookIsEmpty =
      matchingEngineState.buyOrderBookState.orderIdLookup.isEmpty && matchingEngineState.buyOrderBookState.orderIdLookup.isEmpty

    // calcMidPrice logs a warning when order books is empty so don't call it on initial step when order book is empty
    if (simulationMetadata.currentStep == 0 && matchingEngineState.orderBookIsEmpty) {
      quoteVolume
    } else {
      productVolume * calcMidPrice + quoteVolume
    }
  }

  def step(events: List[Event])(implicit
      accountState: AccountState,
      matchingEngineState: MatchingEngineState,
      simulationMetadata: SimulationMetadata
  ): Unit = {
    matchingEngineState.previousPortfolioValue =
      matchingEngineState.currentPortfolioValue
    processEvents(events)
    implicit val walletsState = accountState.walletsState
    matchingEngineState.currentPortfolioValue = Some(calcPortfolioValue)
  }

  private def processMatchedMakerOrder(
      makerOrder: LimitOrderEvent,
      liquidity: Liquidity
  )(implicit
      accountState: AccountState,
      simulationMetadata: SimulationMetadata
  ): LimitOrderEvent = {
    if (makerOrder.remainingSize.isZero) {
      if (liquidity.ismaker) {
        Account.closeOrder(makerOrder, DoneReason.filled)
      } else {
        OrderUtils.setOrderStatusToDone(makerOrder, DoneReason.filled)
      }
    } else {
      makerOrder
    }
  }

  private def processMatchedTakerOrder(
      takerOrder: OrderEvent,
      liquidity: Liquidity
  )(implicit
      accountState: AccountState,
      simulationMetadata: SimulationMetadata
  ): OrderEvent = {
    val isFilled = takerOrder match {
      case takerOrder: SpecifiesSize  => takerOrder.remainingSize.isZero
      case takerOrder: SpecifiesFunds => takerOrder.remainingFunds.isZero
    }

    if (isFilled) {
      if (liquidity.istaker) {
        Account.closeOrder(takerOrder, DoneReason.filled)
      } else {
        OrderUtils.setOrderStatusToDone(takerOrder, DoneReason.filled)
      }
    } else {
      takerOrder
    }
  }

  private def createMatch(
      filledVolume: ProductVolume,
      makerOrder: LimitOrderEvent,
      takerOrder: OrderEvent
  )(implicit
      accountState: AccountState,
      matchingEngineState: MatchingEngineState,
      simulationMetadata: SimulationMetadata
  ): Unit = {
    val liquidity = getLiquidity(makerOrder, takerOrder)

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
      OrderUtils.orderEventToSealedOneOf(makerOrder),
      OrderUtils.orderEventToSealedOneOf(takerOrder)
    )

    if (!liquidity.isglobal) {
      Account.updateBalance(matchEvent)
    }

    val updatedMatchEvent = matchEvent.update(
      _.makerOrder := OrderUtils orderEventToSealedOneOf processMatchedMakerOrder(
        makerOrder,
        liquidity
      ),
      _.takerOrder := OrderUtils orderEventToSealedOneOf processMatchedTakerOrder(
        takerOrder,
        liquidity
      )
    )

    matchingEngineState.matches += updatedMatchEvent

    if (!liquidity.isglobal) {
      Account.addMatch(updatedMatchEvent)
    }
  }

  private def deincrementRemainingSizes(
      makerOrder: LimitOrderEvent,
      takerOrder: SpecifiesSize
  ): ProductVolume = {
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
      Left(
        takerOrder.remainingFunds.amount.divide(
          makerOrder.price.amount,
          ProductVolume.scale,
          RoundingMode.HALF_UP
        )
      )
    )

    val filledVolume =
      List(takerOrderDesiredVolume, makerOrder.remainingSize).min

    val takerOrderRemainingFunds =
      takerOrder.remainingFunds - makerOrder.price * filledVolume

    val makerOrderRemainingSize = List(
      ProductVolume.zeroVolume,
      makerOrder.remainingSize - filledVolume
    ).max

    takerOrder.setRemainingFunds(takerOrderRemainingFunds)
    makerOrder.setRemainingSize(makerOrderRemainingSize)

    filledVolume
  }

  def getBestMakerOrder(implicit
      orderBookState: OrderBookState
  ): Option[LimitOrderEvent] =
    orderBookState match {
      case orderBookState: BuyOrderBookState =>
        OrderBook.maxOrder(orderBookState)
      case orderBookState: SellOrderBookState =>
        OrderBook.minOrder(orderBookState)
    }

  def start(implicit
      matchingEngineState: MatchingEngineState,
      walletsState: WalletsState,
      simulationMetadata: SimulationMetadata
  ): Unit = {
    matchingEngineState.currentPortfolioValue = Some(calcPortfolioValue)
  }

  @tailrec
  private def processBuyMarketOrder(order: BuyMarketOrder)(implicit
      accountState: AccountState,
      matchingEngineState: MatchingEngineState,
      sellOrderBookState: SellOrderBookState,
      simulationMetadata: SimulationMetadata
  ): Unit = {
    if (order.remainingFunds > QuoteVolume.zeroVolume) {
      val makerOrder = getBestMakerOrder

      makerOrder match {
        case None => {
          cancelOrder(order)
        }
        case Some(makerOrder) => {
          val shouldCancel =
            checkForSelfTrade(makerOrder, order) || SlippageProtection
              .checkSlippageGreaterThanMax(makerOrder.price)

          if (shouldCancel) {
            cancelOrder(order)
            return
          }

          val filledVolume = deincrementRemainingSizeAndFunds(makerOrder, order)

          createMatch(filledVolume, makerOrder, order)

          if (makerOrder.remainingSize.isZero) {
            OrderBook.removeByOrderId(makerOrder.orderId)
          }

          processBuyMarketOrder(order)
        }
      }
    }
  }

  @tailrec
  private def processSellMarketOrder(order: SellMarketOrder)(implicit
      accountState: AccountState,
      matchingEngineState: MatchingEngineState,
      buyOrderBookState: BuyOrderBookState,
      simulationMetadata: SimulationMetadata
  ): Unit = {
    if (order.remainingSize > ProductVolume.zeroVolume) {
      val makerOrder = getBestMakerOrder

      makerOrder match {
        case None => {
          cancelOrder(order)
        }
        case Some(makerOrder) => {
          val shouldCancel =
            checkForSelfTrade(makerOrder, order) || SlippageProtection
              .checkSlippageGreaterThanMax(makerOrder.price)

          if (shouldCancel) {
            cancelOrder(order)
            return
          }

          val filledVolume = deincrementRemainingSizes(makerOrder, order)

          createMatch(filledVolume, makerOrder, order)

          if (makerOrder.remainingSize.isZero) {
            OrderBook.removeByOrderId(makerOrder.orderId)
          }

          processSellMarketOrder(order)
        }
      }
    }
  }

  private def processCancellation(
      cancellation: Cancellation
  )(implicit
      accountState: AccountState,
      orderBookState: OrderBookState,
      simulationMetadata: SimulationMetadata
  ): Option[OrderEvent] = {
    OrderBook
      .getOrderByOrderId(cancellation.orderId)
      .map { order =>
        cancelOrder(order)
      }
  }

  private def processMarketOrder(order: MarketOrderEvent)(implicit
      accountState: AccountState,
      matchingEngineState: MatchingEngineState,
      simulationMetadata: SimulationMetadata
  ): Unit = {
    order match {
      case order: BuyMarketOrder =>
        processBuyMarketOrder(order)(
          accountState,
          matchingEngineState,
          matchingEngineState.sellOrderBookState,
          simulationMetadata
        )
      case order: SellMarketOrder =>
        processSellMarketOrder(order)(
          accountState,
          matchingEngineState,
          matchingEngineState.buyOrderBookState,
          simulationMetadata
        )
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
  private def processLimitOrder(
      order: LimitOrderEvent,
      makerOrderBookState: OrderBookState,
      takerOrderBookState: OrderBookState
  )(implicit
      accountState: AccountState,
      matchingEngineState: MatchingEngineState,
      simulationMetadata: SimulationMetadata
  ): Unit = {
    if (!checkIsTaker(order)) {
      addToOrderBook(order)(accountState, takerOrderBookState)
      return
    }

    if (order.remainingSize > ProductVolume.zeroVolume) {
      val makerOrder = getBestMakerOrder(makerOrderBookState)

      makerOrder match {
        case None => {
          addToOrderBook(order)(accountState, takerOrderBookState)
        }
        case Some(makerOrder) => {
          val shouldCancel =
            checkForSelfTrade(makerOrder, order) || SlippageProtection
              .checkSlippageGreaterThanMax(makerOrder.price)

          if (shouldCancel) {
            cancelOrder(order)(
              accountState,
              takerOrderBookState,
              simulationMetadata
            )
            return
          }

          val filledVolume = deincrementRemainingSizes(makerOrder, order)

          createMatch(filledVolume, makerOrder, order)

          if (makerOrder.remainingSize.isZero) {
            OrderBook.removeByKey(makerOrder.orderBookKey)(makerOrderBookState)
          }

          processLimitOrder(order, makerOrderBookState, takerOrderBookState)
        }
      }
    }
  }

  private def processOrder(order: OrderEvent)(implicit
      accountState: AccountState,
      matchingEngineState: MatchingEngineState,
      simulationMetadata: SimulationMetadata
  ): Unit = {
    SlippageProtection.reset
    order match {
      case order: BuyLimitOrder =>
        processLimitOrder(
          order,
          matchingEngineState.sellOrderBookState,
          matchingEngineState.buyOrderBookState
        )
      case order: SellLimitOrder =>
        processLimitOrder(
          order,
          matchingEngineState.buyOrderBookState,
          matchingEngineState.sellOrderBookState
        )
      case order: MarketOrderEvent => processMarketOrder(order)
    }
  }
}

object SlippageProtection {
  val maxPriceSlippagePoints = new BigDecimal("0.1")
  private var firstMatchPrice: Option[ProductPrice] = None
  private var priceSlippagePoints = new BigDecimal("0.0")

  def checkSlippageGreaterThanMax(makerOrderPrice: ProductPrice) = {
    firstMatchPrice match {
      case None => firstMatchPrice = Some(makerOrderPrice)
      case Some(firstMatchPrice) =>
        priceSlippagePoints =
          getPriceSlippagePoints(firstMatchPrice, makerOrderPrice)

    }

    priceSlippagePoints.compareTo(
      SlippageProtection.maxPriceSlippagePoints
    ) >= 0
  }

  def getPriceSlippagePoints(
      firstMatchPrice: ProductPrice,
      makerOrderPrice: ProductPrice
  ): BigDecimal = {
    makerOrderPrice.amount
      .subtract(firstMatchPrice.amount)
      .divide(firstMatchPrice.amount, 6, RoundingMode.HALF_UP)
      .abs
  }

  def reset() = {
    firstMatchPrice = None
    priceSlippagePoints = new BigDecimal("0.0")
  }
}

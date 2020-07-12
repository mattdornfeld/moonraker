package co.firstorderlabs.fakebase

import java.util.UUID

import co.firstorderlabs.fakebase.currency.Configs.ProductPrice
import co.firstorderlabs.fakebase.currency.Configs.ProductPrice.{
  ProductVolume,
  QuoteVolume
}
import co.firstorderlabs.fakebase.currency.Volume.Volume
import co.firstorderlabs.fakebase.protos.fakebase.{
  AccountServiceGrpc,
  BuyLimitOrder,
  BuyLimitOrderRequest,
  BuyMarketOrder,
  BuyMarketOrderRequest,
  Cancellation,
  CancellationRequest,
  DoneReason,
  Match,
  MatchEvents,
  Order,
  OrderSide,
  OrderStatus,
  Orders,
  RejectReason,
  SellLimitOrder,
  SellLimitOrderRequest,
  SellMarketOrder,
  SellMarketOrderRequest,
  Wallets => WalletsProto
}
import co.firstorderlabs.fakebase.types.Events._
import co.firstorderlabs.fakebase.types.Types.{
  OrderId,
  OrderRequestId,
  TimeInterval
}
import com.google.protobuf.empty.Empty
import io.grpc.Status

import scala.collection.mutable.{HashMap, ListBuffer}
import scala.concurrent.Future

class CancellationsHashMap
    extends HashMap[TimeInterval, ListBuffer[Cancellation]] {
  override def clone: CancellationsHashMap = {
    val clonedMap = new CancellationsHashMap
    clonedMap.addAll(super.clone.iterator)
  }

  override def apply(key: TimeInterval): ListBuffer[Cancellation] =
    super.getOrElseUpdate(key, ListBuffer())
}

class MatchesHashMap extends HashMap[OrderId, ListBuffer[Match]] {
  override def clone: MatchesHashMap = {
    val clonedMap = new MatchesHashMap
    clonedMap.addAll(super.clone.iterator)
  }

  override def apply(key: OrderId): ListBuffer[Match] =
    super.getOrElseUpdate(key, ListBuffer())

}

case class AccountCheckpoint(
  orderRequests: HashMap[OrderRequestId, OrderRequest],
  placedCancellations: CancellationsHashMap,
  placedOrders: HashMap[OrderId, OrderEvent],
  matches: MatchesHashMap,
  walletsCheckpoint: WalletsCheckpoint,
  matchesInCurrentTimeInterval: ListBuffer[Match]
) extends Checkpoint

object Account
    extends AccountServiceGrpc.AccountService
    with Checkpointable[AccountCheckpoint] {
  val matches = new MatchesHashMap
  val placedOrders = new HashMap[OrderId, OrderEvent]
  private val orderRequests = new HashMap[OrderRequestId, OrderRequest]
  private val placedCancellations = new CancellationsHashMap
  private val matchesInCurrentTimeInterval = new ListBuffer[Match]

  def addFunds[A <: Volume[A]](volume: A): Unit = {
    Wallets.addFunds(volume)
  }

  def addMatch(matchEvent: Match): Unit = {
    matchesInCurrentTimeInterval append matchEvent
    val orderId = matchEvent.getAccountOrder.get.orderId
    matches(orderId) append matchEvent
  }

  def belongsToAccount(order: OrderEvent): Boolean = {
    placedOrders.contains(order.orderId)
  }

  def checkpoint: AccountCheckpoint =
    AccountCheckpoint(
      orderRequests.clone,
      placedCancellations.clone,
      placedOrders.clone,
      matches.clone,
      Wallets.checkpoint,
      matchesInCurrentTimeInterval.clone
    )

  def clear: Unit = {
    orderRequests.clear
    placedCancellations.clear
    placedOrders.clear
    matches.clear
    Wallets.clear
    matchesInCurrentTimeInterval.clear
  }

  def closeOrder[A <: OrderEvent](order: A,
                                  doneReason: DoneReason): Option[A] = {
    if (placedOrders.contains(order.orderId))
      if (!List(OrderStatus.received, OrderStatus.open).contains(
            order.orderStatus
          )) return None

    Wallets.removeHolds(order)

    val updatedOrder = OrderUtils.setOrderStatusToDone(order, doneReason)
    placedOrders.update(updatedOrder.orderId, updatedOrder)
    Some(updatedOrder)
  }

  def isCleared: Boolean = {
    (orderRequests.isEmpty
    && placedCancellations.isEmpty
    && placedOrders.isEmpty
    && matches.isEmpty
    && Wallets.isCleared
    && matchesInCurrentTimeInterval.isEmpty)
  }

  def openOrder(orderId: OrderId): Option[LimitOrderEvent] = {
    val order = placedOrders.get(orderId)
    order match {
      case Some(order) => {
        order match {
          case order: LimitOrderEvent => {
            val updatedOrder = OrderUtils.openOrder(order)
            placedOrders.update(orderId, updatedOrder)
            Some(updatedOrder)
          }
          case _ => None
        }
      }
      case None => None
    }
  }

  def getOrderRequest[A <: OrderRequest](
    orderRequestId: OrderRequestId
  ): Option[A] = {
    if (orderRequests.contains(orderRequestId))
      Some(orderRequests.get(orderRequestId).get.asInstanceOf[A])
    else
      None
  }

  def getReceivedCancellations: Iterable[Cancellation] = {
    val timeInterval = Exchange.simulationMetadata.get.currentTimeInterval - Exchange.simulationMetadata.get.timeDelta
    for (cancellation <- placedCancellations(timeInterval))
      yield cancellation
  }

  def getReceivedOrders: Iterable[OrderEvent] = {
    for (order <- placedOrders.values
         if order.orderStatus == OrderStatus.received)
      yield order
  }

  def hasSufficientFunds(order: BuyOrderEvent): Boolean = {
    val requiredBuyHold = Wallets.calcRequiredBuyHold(order)
    requiredBuyHold > Wallets.getAvailableFunds(QuoteVolume)
  }

  def hasSufficientSize(order: SpecifiesSize): Boolean = {
    val productFunds = Wallets.getAvailableFunds(ProductVolume)
    order.size > productFunds
  }

  def initializeWallets: Unit = Wallets.initializeWallets

  def restore(checkpoint: AccountCheckpoint): Unit = {
    clear
    orderRequests.addAll(checkpoint.orderRequests.iterator)
    placedCancellations.addAll(checkpoint.placedCancellations.iterator)
    placedOrders.addAll(checkpoint.placedOrders.iterator)
    matches.addAll(checkpoint.matches.iterator)
    Wallets.restore(checkpoint.walletsCheckpoint)
    matchesInCurrentTimeInterval.addAll(checkpoint.matchesInCurrentTimeInterval)
  }

  def step: Unit = {
    matchesInCurrentTimeInterval.clear
  }

  def updateBalance(matchEvent: MatchEvent): Unit = {
    Wallets.updateBalances(matchEvent)
  }

  override def cancelOrder(
    cancellationRequest: CancellationRequest
  ): Future[Cancellation] = {
    val order = placedOrders.get(cancellationRequest.orderId)

    if (order.isEmpty) {
      return Future.failed(
        Status.NOT_FOUND
          .augmentDescription("orderId not found")
          .asRuntimeException
      )
    }

    if (!(order.get.orderStatus.isopen || order.get.orderStatus.isreceived)) {
      return Future.failed(
        Status.UNAVAILABLE
          .augmentDescription(
            "can only cancel orders with status open or received"
          )
          .asRuntimeException
      )
    }

    order.get match {
      case order: LimitOrderEvent => {
        val cancellation = OrderUtils.cancellationFromOrder(order)

        placedCancellations(Exchange.simulationMetadata.get.currentTimeInterval) += cancellation

        Future.successful(cancellation)
      }
      case _ => {
        Future.failed(
          Status.UNAVAILABLE
            .augmentDescription("can only cancel limit orders")
            .asRuntimeException
        )
      }
    }
  }

  override def getMatches(request: Empty): Future[MatchEvents] = {
    val matchEvents = MatchEvents(matchesInCurrentTimeInterval.toSeq)
    Future.successful(matchEvents)
  }

  override def getOrders(request: Empty): Future[Orders] = {
    val orders = Orders(
      placedOrders
        .map(
          item =>
            (
              item._1,
              OrderUtils
                .addMatchesToOrder(item._2, matches(item._2.orderId).toSeq)
          )
        )
        .map(
          item => (item._1.orderId, OrderUtils.orderEventToSealedOneOf(item._2))
        )
        .toMap[String, Order]
    )

    Future.successful(orders)
  }

  override def getWallets(request: Empty): Future[WalletsProto] =
    Future.successful(Wallets.toProto)

  override def placeBuyMarketOrder(
    buyMarketOrderRequest: BuyMarketOrderRequest
  ): Future[BuyMarketOrder] = {
    val orderRequestId = storeOrderRequest(buyMarketOrderRequest)

    val buyMarketOrder = BuyMarketOrder(
      funds = buyMarketOrderRequest.funds,
      orderId = OrderUtils.generateOrderId,
      orderStatus = OrderStatus.received,
      productId = buyMarketOrderRequest.productId,
      side = OrderSide.buy,
      time = Exchange.simulationMetadata.get.currentTimeInterval.endTime,
      rejectReason = RejectReason.notRejected,
      requestId = orderRequestId,
      matchEvents = Some(MatchEvents())
    )

    Future.successful(processOrder(buyMarketOrder))
  }

  override def placeBuyLimitOrder(
    buyLimitOrderRequest: BuyLimitOrderRequest
  ): Future[BuyLimitOrder] = {
    val orderRequestId = storeOrderRequest(buyLimitOrderRequest)

    val buyLimitOrder = new BuyLimitOrder(
      orderId = OrderUtils.generateOrderId,
      orderStatus = OrderStatus.received,
      price = buyLimitOrderRequest.price,
      productId = buyLimitOrderRequest.productId,
      side = OrderSide.buy,
      size = buyLimitOrderRequest.size,
      time = Exchange.simulationMetadata.get.currentTimeInterval.endTime,
      rejectReason = RejectReason.notRejected,
      requestId = orderRequestId,
      matchEvents = Some(MatchEvents())
    )

    Future.successful(processOrder(buyLimitOrder))
  }

  override def placeSellLimitOrder(
    sellLimitOrderRequest: SellLimitOrderRequest
  ): Future[SellLimitOrder] = {
    val orderRequestId = storeOrderRequest(sellLimitOrderRequest)

    val sellLimitOrder = new SellLimitOrder(
      orderId = OrderUtils.generateOrderId,
      orderStatus = OrderStatus.received,
      price = sellLimitOrderRequest.price,
      productId = sellLimitOrderRequest.productId,
      side = OrderSide.sell,
      size = sellLimitOrderRequest.size,
      time = Exchange.simulationMetadata.get.currentTimeInterval.endTime,
      rejectReason = RejectReason.notRejected,
      requestId = orderRequestId,
      matchEvents = Some(MatchEvents())
    )

    Future.successful(processOrder(sellLimitOrder))
  }

  override def placeSellMarketOrder(
    sellMarketOrderRequest: SellMarketOrderRequest
  ): Future[SellMarketOrder] = {
    val orderRequestId = storeOrderRequest(sellMarketOrderRequest)

    val sellMarketOrder = SellMarketOrder(
      orderId = OrderUtils.generateOrderId,
      orderStatus = OrderStatus.received,
      productId = sellMarketOrderRequest.productId,
      side = OrderSide.sell,
      size = sellMarketOrderRequest.size,
      time = Exchange.simulationMetadata.get.currentTimeInterval.endTime,
      rejectReason = RejectReason.notRejected,
      requestId = orderRequestId,
      matchEvents = Some(MatchEvents())
    )

    Future.successful(processOrder(sellMarketOrder))
  }

  private def processOrder[A <: OrderEvent](order: A): A = {
    val rejectReason = OrderRejecter.getRejectReason(order)
    if (rejectReason.isDefined) {
      val rejectedOrder = OrderUtils.rejectOrder(order, rejectReason.get)
      placedOrders.update(rejectedOrder.orderId, rejectedOrder)
      rejectedOrder
    } else {
      Wallets.incrementHolds(order)
      placedOrders.update(order.orderId, order)
      order
    }
  }

  private def storeOrderRequest(orderRequest: OrderRequest): OrderRequestId = {
    val orderRequestId = OrderRequestId(UUID.randomUUID().toString)
    orderRequests.update(orderRequestId, orderRequest)
    orderRequestId
  }
}

object OrderRejecter {
  def isBuyMarketOrderInvalid(
    buyMarketOrder: BuyMarketOrder
  ): Option[RejectReason] = {
    List(isFundsInvalid _, hasInsufficientFunds _).view
      .flatMap(f => f(buyMarketOrder))
      .headOption
  }

  private def isFundsInvalid(
    buyMarketOrder: BuyMarketOrder
  ): Option[RejectReason] = {
    buyMarketOrder.funds match {
      case funds if funds > QuoteVolume.maxVolume =>
        Some(RejectReason.fundsTooLarge)
      case funds if funds < QuoteVolume.minVolume =>
        Some(RejectReason.fundsTooSmall)
      case _ => None
    }
  }

  def isLimitOrderInvalid(limitOrder: LimitOrderEvent): Option[RejectReason] = {
    List(
      isPriceInvalid _,
      isSizeInvalid _,
      violatesPostOnly _,
      hasInsufficientFunds _
    ).view
      .flatMap(f => f(limitOrder))
      .headOption
  }

  def getRejectReason[A <: OrderEvent](order: A): Option[RejectReason] = {
    order match {
      case order: LimitOrderEvent => OrderRejecter.isLimitOrderInvalid(order)
      case order: BuyMarketOrder =>
        OrderRejecter.isBuyMarketOrderInvalid(order)
      case order: SellMarketOrder =>
        OrderRejecter.isSellMarketOrderInvalid(order)
    }
  }

  private def hasInsufficientFunds(order: OrderEvent): Option[RejectReason] = {
    order match {
      case order: BuyOrderEvent => {
        if (Account.hasSufficientFunds(order))
          Some(RejectReason.insufficientFunds)
        else None
      }
      case order: SellOrderEvent => {
        if (Account.hasSufficientSize(order))
          Some(RejectReason.insufficientFunds)
        else None
      }
    }
  }

  private def isPriceInvalid(
    limitOrder: LimitOrderEvent
  ): Option[RejectReason] = {
    limitOrder.price match {
      case price if price > ProductPrice.maxPrice =>
        Some(RejectReason.priceTooLarge)
      case price if price < ProductPrice.minPrice =>
        Some(RejectReason.priceTooSmall)
      case _ => None
    }
  }

  private def isSizeInvalid(order: SpecifiesSize): Option[RejectReason] = {
    order.size match {
      case size if size > ProductVolume.maxVolume =>
        Some(RejectReason.sizeTooLarge)
      case size if size < ProductVolume.minVolume =>
        Some(RejectReason.sizeTooSmall)
      case _ => None
    }
  }

  private def violatesPostOnly(
    limitOrder: LimitOrderEvent
  ): Option[RejectReason] = {
    val orderRequest =
      Account.getOrderRequest[LimitOrderRequest](limitOrder.requestId)
    val violatesPostOnly = Exchange.checkIsTaker(limitOrder) && orderRequest.get.postOnly
    if (violatesPostOnly) Some(RejectReason.postOnly) else None
  }

  def isSellMarketOrderInvalid(
    sellMarketOrder: SellMarketOrder
  ): Option[RejectReason] = {
    List(isSizeInvalid _, hasInsufficientFunds _).view
      .flatMap(f => f(sellMarketOrder))
      .headOption
  }
}

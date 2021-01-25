package co.firstorderlabs.coinbaseml.fakebase

import java.util.UUID

import co.firstorderlabs.coinbaseml.common.utils.Utils.getResultOptional
import co.firstorderlabs.coinbaseml.fakebase.Types.Exceptions.{
  InvalidOrderStatus,
  InvalidOrderType,
  OrderNotFound
}
import co.firstorderlabs.coinbaseml.fakebase.utils.OrderUtils
import co.firstorderlabs.common.currency.Configs.ProductPrice
import co.firstorderlabs.common.currency.Configs.ProductPrice.{
  ProductVolume,
  QuoteVolume
}
import co.firstorderlabs.common.currency.Volume.Volume
import co.firstorderlabs.common.protos.events
import co.firstorderlabs.common.protos.events.{
  BuyLimitOrder,
  BuyMarketOrder,
  Cancellation,
  DoneReason,
  Match,
  MatchEvents,
  Order,
  OrderSide,
  OrderStatus,
  Orders,
  RejectReason,
  SellLimitOrder,
  SellMarketOrder
}
import co.firstorderlabs.common.protos.fakebase.{
  AccountInfo,
  AccountServiceGrpc,
  BuyLimitOrderRequest,
  BuyMarketOrderRequest,
  CancellationRequest,
  SellLimitOrderRequest,
  SellMarketOrderRequest,
  Wallets => WalletsProto
}
import co.firstorderlabs.common.types.Events.{
  LimitOrderRequest,
  OrderRequest,
  _
}
import co.firstorderlabs.common.types.Types.{
  OrderId,
  OrderRequestId,
  SimulationId,
  TimeInterval
}
import io.grpc.Status

import scala.collection.mutable
import scala.collection.mutable.{HashMap, ListBuffer}
import scala.concurrent.Future

final class CancellationsHashMap
    extends HashMap[TimeInterval, ListBuffer[Cancellation]] {
  override def clone: CancellationsHashMap = {
    val clonedMap = new CancellationsHashMap
    clonedMap.addAll(super.clone.iterator)
  }

  override def apply(key: TimeInterval): ListBuffer[Cancellation] =
    super.getOrElseUpdate(key, ListBuffer())
}

final class MatchesHashMap extends HashMap[OrderId, ListBuffer[Match]] {
  override def clone: MatchesHashMap = {
    val clonedMap = new MatchesHashMap
    clonedMap.addAll(super.clone.iterator)
  }

  override def apply(key: OrderId): ListBuffer[Match] =
    super.getOrElseUpdate(key, ListBuffer())
}

final case class AccountState(
    orderRequests: HashMap[OrderRequestId, OrderRequest],
    placedCancellations: CancellationsHashMap,
    placedOrders: mutable.HashMap[OrderId, OrderEvent],
    matches: MatchesHashMap,
    walletsState: WalletsState,
    matchesInCurrentTimeInterval: ListBuffer[Match]
) extends State[AccountState] {
  override val companion = AccountState

  override def createSnapshot(implicit
      simulationState: SimulationState
  ): AccountState =
    AccountState(
      orderRequests.clone,
      placedCancellations.clone,
      placedOrders.clone,
      matches.clone,
      walletsState.createSnapshot,
      matchesInCurrentTimeInterval.clone
    )

}

object AccountState extends StateCompanion[AccountState] {
  override def create(implicit
      simulationMetadata: SimulationMetadata
  ): AccountState =
    AccountState(
      new mutable.HashMap,
      new CancellationsHashMap,
      new mutable.HashMap,
      new MatchesHashMap,
      WalletsState.create,
      new ListBuffer
    )

  override def fromSnapshot(snapshot: AccountState): AccountState = {
    val accountState = AccountState(
      new mutable.HashMap,
      new CancellationsHashMap,
      new mutable.HashMap,
      new MatchesHashMap,
      WalletsState.fromSnapshot(snapshot.walletsState),
      new ListBuffer
    )
    accountState.orderRequests.addAll(snapshot.orderRequests.iterator)
    accountState.placedCancellations.addAll(
      snapshot.placedCancellations.iterator
    )
    accountState.placedOrders.addAll(snapshot.placedOrders.iterator)
    accountState.matches.addAll(snapshot.matches.iterator)
    accountState.matchesInCurrentTimeInterval.addAll(
      snapshot.matchesInCurrentTimeInterval
    )
    accountState
  }
}

object Account extends AccountServiceGrpc.AccountService {

  def addFunds[A <: Volume[A]](
      volume: A
  )(implicit accountState: AccountState): Unit = {
    Wallets.addFunds(volume)(accountState.walletsState)
  }

  def addMatch(matchEvent: Match)(implicit accountState: AccountState): Unit = {
    accountState.matchesInCurrentTimeInterval append matchEvent
    val orderId = matchEvent.getAccountOrder.get.orderId
    accountState.matches(orderId) append matchEvent
  }

  def belongsToAccount(
      order: OrderEvent
  )(implicit accountState: AccountState): Boolean = {
    accountState.placedOrders.contains(order.orderId)
  }

  def cancelExpiredOrders(implicit
      accountState: AccountState,
      simulationMetadata: SimulationMetadata
  ): Unit = {
    accountState.placedOrders
      .filter(item =>
        item._2.orderStatus.isopen || item._2.orderStatus.isreceived
      )
      .foreach { item =>
        item._2 match {
          case order: LimitOrderEvent => {
            if (
              order.isExpired(
                simulationMetadata.currentTimeInterval
              )
            ) {
              val cancellationRequest = CancellationRequest(
                order.orderId,
                simulationId = Some(simulationMetadata.simulationId)
              )
              cancelOrder(cancellationRequest)
            }
          }
          case _ =>
        }
      }
  }

  @throws[OrderNotFound]
  @throws[InvalidOrderStatus]
  def closeOrder[A <: OrderEvent](order: A, doneReason: DoneReason)(implicit
      accountState: AccountState,
      simulationMetadata: SimulationMetadata
  ): A = {
    if (!accountState.placedOrders.contains(order.orderId))
      throw OrderNotFound(
        s"orderId ${order.orderId} was not found in Account.placedOrders"
      )

    if (
      !List(OrderStatus.received, OrderStatus.open).contains(
        order.orderStatus
      )
    )
      throw InvalidOrderStatus(
        s"${order.orderStatus} not in List(OrderStatus.received, OrderStatus.open)"
      )

    Wallets.removeHolds(order)(accountState.walletsState)

    val updatedOrder = OrderUtils.setOrderStatusToDone(order, doneReason)
    accountState.placedOrders.update(updatedOrder.orderId, updatedOrder)
    updatedOrder
  }

  def openOrder(
      orderId: OrderId
  )(implicit accountState: AccountState): LimitOrderEvent = {
    val order = accountState.placedOrders.get(orderId)
    order match {
      case Some(order) => {
        order match {
          case order: LimitOrderEvent => {
            val updatedOrder = OrderUtils.openOrder(order)
            accountState.placedOrders.update(orderId, updatedOrder)
            updatedOrder
          }
          case order: OrderEvent =>
            throw InvalidOrderType(
              s"tried to open order of type ${order.getClass}. Can only open orders that have trait LimitOrderEvent"
            )
        }
      }
      case None =>
        throw OrderNotFound(
          s"orderId ${orderId} was not found in Account.placedOrders"
        )
    }
  }

  def getOrderRequest[A <: OrderRequest](
      orderRequestId: OrderRequestId
  )(implicit accountState: AccountState): Option[A] = {
    if (accountState.orderRequests.contains(orderRequestId))
      Some(accountState.orderRequests.get(orderRequestId).get.asInstanceOf[A])
    else
      None
  }

  def getFilteredOrders(
      filter: OrderEvent => Boolean
  )(implicit accountState: AccountState): Iterable[OrderEvent] =
    for (
      order <- accountState.placedOrders.values
      if filter(order)
    )
      yield order

  def getReceivedCancellations(implicit
      accountState: AccountState,
      simulationMetadata: SimulationMetadata
  ): Iterable[Cancellation] = {
    val timeInterval =
      simulationMetadata.currentTimeInterval - simulationMetadata.timeDelta
    for (cancellation <- accountState.placedCancellations(timeInterval))
      yield cancellation
  }

  def getReceivedOrders(implicit
      accountState: AccountState
  ): Iterable[OrderEvent] =
    getFilteredOrders(_.orderStatus.isreceived)

  def hasSufficientFunds(
      order: BuyOrderEvent
  )(implicit
      accountState: AccountState,
      matchingEngineState: MatchingEngineState
  ): Boolean = {
    val requiredBuyHold = Wallets.calcRequiredBuyHold(order)
    requiredBuyHold > Wallets.getAvailableFunds(QuoteVolume)(
      accountState.walletsState
    )
  }

  def hasSufficientSize(
      order: SpecifiesSize
  )(implicit accountState: AccountState): Boolean = {
    val productFunds =
      Wallets.getAvailableFunds(ProductVolume)(accountState.walletsState)
    order.size > productFunds
  }

  def initializeWallets(implicit accountState: AccountState): Unit =
    Wallets.initializeWallets(accountState.walletsState)

  def step(implicit
      accountState: AccountState,
      simulationMetadata: SimulationMetadata
  ): Unit = {
    cancelExpiredOrders
    accountState.matchesInCurrentTimeInterval.clear
  }

  def updateBalance(
      matchEvent: MatchEvent
  )(implicit accountState: AccountState): Unit = {
    Wallets.updateBalances(matchEvent)(accountState.walletsState)
  }

  override def cancelOrder(
      cancellationRequest: CancellationRequest
  ): Future[Cancellation] = {
    val simulationState =
      SimulationState.getOrFail(cancellationRequest.simulationId.get)
    val accountState = simulationState.accountState
    implicit val simulationMetadata = simulationState.simulationMetadata
    val order = accountState.placedOrders.get(cancellationRequest.orderId)

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

        accountState.placedCancellations(
          simulationMetadata.currentTimeInterval
        ) += cancellation

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

  override def getAccountInfo(
      simulationId: SimulationId
  ): Future[AccountInfo] = {
    Future.successful(
      AccountInfo(
        getResultOptional(getWallets(simulationId)),
        getResultOptional(getMatches(simulationId))
      )
    )
  }

  override def getMatches(simulationId: SimulationId): Future[MatchEvents] = {
    val accountState = SimulationState.getAccountStateOrFail(simulationId)
    val matchEvents =
      events.MatchEvents(accountState.matchesInCurrentTimeInterval.toSeq)
    Future.successful(matchEvents)
  }

  override def getOrders(simulationId: SimulationId): Future[Orders] = {
    val accountState = SimulationState.getAccountStateOrFail(simulationId)
    val orders = Orders(
      accountState.placedOrders
        .map(item =>
          (
            item._1,
            OrderUtils
              .addMatchesToOrder(
                item._2,
                accountState.matches(item._2.orderId).toSeq
              )
          )
        )
        .map(item =>
          (item._1.orderId, OrderUtils.orderEventToSealedOneOf(item._2))
        )
        .toMap[String, Order]
    )

    Future.successful(orders)
  }

  override def getWallets(simulationId: SimulationId): Future[WalletsProto] =
    Future.successful(
      Wallets.toProto(
        SimulationState.getAccountStateOrFail(simulationId).walletsState
      )
    )

  override def placeBuyMarketOrder(
      buyMarketOrderRequest: BuyMarketOrderRequest
  ): Future[BuyMarketOrder] = {
    val simulationState = SimulationState.getOrFail(
      buyMarketOrderRequest.simulationId.get
    )
    val simulationMetadata = simulationState.simulationMetadata
    implicit val accountState = simulationState.accountState
    implicit val matchingEngineState = simulationState.matchingEngineState

    val orderRequestId = storeOrderRequest(buyMarketOrderRequest)

    val buyMarketOrder = BuyMarketOrder(
      funds = buyMarketOrderRequest.funds,
      orderId = OrderUtils.generateOrderId,
      orderStatus = OrderStatus.received,
      productId = buyMarketOrderRequest.productId,
      side = OrderSide.buy,
      time = simulationMetadata.currentTimeInterval.endTime,
      rejectReason = RejectReason.notRejected,
      requestId = orderRequestId,
      matchEvents = Some(MatchEvents())
    )

    Future.successful(processOrder(buyMarketOrder))
  }

  override def placeBuyLimitOrder(
      buyLimitOrderRequest: BuyLimitOrderRequest
  ): Future[BuyLimitOrder] = {
    val simulationState =
      SimulationState.getOrFail(buyLimitOrderRequest.simulationId.get)
    val simulationMetadata = simulationState.simulationMetadata
    implicit val accountState = simulationState.accountState
    implicit val matchingEngineState = simulationState.matchingEngineState
    val orderRequestId = storeOrderRequest(buyLimitOrderRequest)

    val buyLimitOrder = new BuyLimitOrder(
      orderId = OrderUtils.generateOrderId,
      orderStatus = OrderStatus.received,
      price = buyLimitOrderRequest.price,
      productId = buyLimitOrderRequest.productId,
      side = OrderSide.buy,
      size = buyLimitOrderRequest.size,
      time = simulationMetadata.currentTimeInterval.endTime,
      rejectReason = RejectReason.notRejected,
      requestId = orderRequestId,
      matchEvents = Some(MatchEvents()),
      timeToLive = buyLimitOrderRequest.timeToLive
    )

    Future.successful(processOrder(buyLimitOrder))
  }

  override def placeSellLimitOrder(
      sellLimitOrderRequest: SellLimitOrderRequest
  ): Future[SellLimitOrder] = {
    val simulationState =
      SimulationState.getOrFail(sellLimitOrderRequest.simulationId.get)
    val simulationMetadata = simulationState.simulationMetadata
    implicit val accountState = simulationState.accountState
    implicit val matchingEngineState = simulationState.matchingEngineState
    val orderRequestId = storeOrderRequest(sellLimitOrderRequest)

    val sellLimitOrder = new SellLimitOrder(
      orderId = OrderUtils.generateOrderId,
      orderStatus = OrderStatus.received,
      price = sellLimitOrderRequest.price,
      productId = sellLimitOrderRequest.productId,
      side = OrderSide.sell,
      size = sellLimitOrderRequest.size,
      time = simulationMetadata.currentTimeInterval.endTime,
      rejectReason = RejectReason.notRejected,
      requestId = orderRequestId,
      matchEvents = Some(MatchEvents()),
      timeToLive = sellLimitOrderRequest.timeToLive
    )

    Future.successful(processOrder(sellLimitOrder))
  }

  override def placeSellMarketOrder(
      sellMarketOrderRequest: SellMarketOrderRequest
  ): Future[SellMarketOrder] = {
    val simulationState =
      SimulationState.getOrFail(sellMarketOrderRequest.simulationId.get)
    val simulationMetadata = simulationState.simulationMetadata
    implicit val accountState = simulationState.accountState
    implicit val matchingEngineState = simulationState.matchingEngineState
    val orderRequestId = storeOrderRequest(sellMarketOrderRequest)

    val sellMarketOrder = SellMarketOrder(
      orderId = OrderUtils.generateOrderId,
      orderStatus = OrderStatus.received,
      productId = sellMarketOrderRequest.productId,
      side = OrderSide.sell,
      size = sellMarketOrderRequest.size,
      time = simulationMetadata.currentTimeInterval.endTime,
      rejectReason = RejectReason.notRejected,
      requestId = orderRequestId,
      matchEvents = Some(MatchEvents())
    )

    Future.successful(processOrder(sellMarketOrder))
  }

  private def processOrder[A <: OrderEvent](
      order: A
  )(implicit
      accountState: AccountState,
      matchingEngineState: MatchingEngineState
  ): A = {
    implicit val walletsState = accountState.walletsState
    val rejectReason = OrderRejecter.getRejectReason(order)
    if (rejectReason.isDefined) {
      val rejectedOrder = OrderUtils.rejectOrder(order, rejectReason.get)
      accountState.placedOrders.update(rejectedOrder.orderId, rejectedOrder)
      rejectedOrder
    } else {
      Wallets.incrementHolds(order)
      accountState.placedOrders.update(order.orderId, order)
      order
    }
  }

  private def storeOrderRequest(
      orderRequest: OrderRequest
  )(implicit accountState: AccountState): OrderRequestId = {
    val orderRequestId = OrderRequestId(UUID.randomUUID().toString)
    accountState.orderRequests.update(orderRequestId, orderRequest)
    orderRequestId
  }
}

object OrderRejecter {
  def isBuyMarketOrderInvalid(
      buyMarketOrder: BuyMarketOrder
  )(implicit
      accountState: AccountState,
      matchingEngineState: MatchingEngineState
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

  def isLimitOrderInvalid(
      limitOrder: LimitOrderEvent
  )(implicit
      accountState: AccountState,
      matchingEngineState: MatchingEngineState
  ): Option[RejectReason] = {
    List(
      isPriceInvalid _,
      isSizeInvalid _,
      violatesPostOnly _,
      hasInsufficientFunds _
    ).view
      .flatMap(f => f(limitOrder))
      .headOption
  }

  def getRejectReason[A <: OrderEvent](
      order: A
  )(implicit
      accountState: AccountState,
      matchingEngineState: MatchingEngineState
  ): Option[RejectReason] = {
    order match {
      case order: LimitOrderEvent => OrderRejecter.isLimitOrderInvalid(order)
      case order: BuyMarketOrder =>
        OrderRejecter.isBuyMarketOrderInvalid(order)
      case order: SellMarketOrder =>
        OrderRejecter.isSellMarketOrderInvalid(order)
    }
  }

  private def hasInsufficientFunds(
      order: OrderEvent
  )(implicit
      accountState: AccountState,
      matchingEngineState: MatchingEngineState
  ): Option[RejectReason] = {
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
  )(implicit
      accountState: AccountState,
      matchingEngineState: MatchingEngineState
  ): Option[RejectReason] = {
    val orderRequest =
      Account.getOrderRequest[LimitOrderRequest](limitOrder.requestId)
    val violatesPostOnly =
      Exchange.checkIsTaker(limitOrder) && orderRequest.get.postOnly
    if (violatesPostOnly) Some(RejectReason.postOnly) else None
  }

  def isSellMarketOrderInvalid(
      sellMarketOrder: SellMarketOrder
  )(implicit
      accountState: AccountState,
      matchingEngineState: MatchingEngineState
  ): Option[RejectReason] = {
    List(isSizeInvalid _, hasInsufficientFunds _).view
      .flatMap(f => f(sellMarketOrder))
      .headOption
  }
}

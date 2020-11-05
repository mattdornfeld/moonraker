package co.firstorderlabs.coinbaseml.fakebase.utils

import java.util.UUID

import co.firstorderlabs.coinbaseml.fakebase.Exchange
import co.firstorderlabs.common.protos.events.{BuyLimitOrder, BuyMarketOrder, Cancellation, DoneReason, Match, Order, OrderMessage, OrderSide, OrderStatus, RejectReason, SellLimitOrder, SellMarketOrder}
import co.firstorderlabs.common.types.Events._
import co.firstorderlabs.common.types.Types.OrderId

object OrderUtils {
  def addMatchesToOrder[A <: OrderEvent](order: A, matchEvents: Seq[Match]): A = {
    val _matchEvents = order.matchEvents.get.addAllMatchEvents(matchEvents)
    val updatedOrder = order match {
      case order: BuyLimitOrder =>
        order.update(_.matchEvents := _matchEvents)
      case order: BuyMarketOrder =>
        order.update(_.matchEvents := _matchEvents)
      case order: SellLimitOrder =>
        order.update(_.matchEvents := _matchEvents)
      case order: SellMarketOrder =>
        order.update(_.matchEvents := _matchEvents)
    }

    copyVars(order, updatedOrder)
    updatedOrder.asInstanceOf[A]
  }
  def cancellationFromOrder(order: LimitOrderEvent): Cancellation = {
    Cancellation(
      order.orderId,
      order.price,
      order.productId,
      order.side,
      order.remainingSize,
      Exchange.getSimulationMetadata.currentTimeInterval.endTime
    )
  }

  def copyVars[A <: OrderEvent](order: A, updatedOrder: A): A = {
    (order, updatedOrder) match {
      case (order: SpecifiesFunds, updatedOrder: SpecifiesFunds) =>
        updatedOrder.copySpecifiesFundsVars(order)
      case (order: SpecifiesSize, updatedOrder: SpecifiesSize) =>
        updatedOrder.copySpecifiesSizeVars(order)
    }

    (order, updatedOrder) match {
      case (order: BuyOrderEvent, updatedOrder: BuyOrderEvent) =>
        updatedOrder.copyBuyOrderEventVars(order)
      case (order: SellOrderEvent, updatedOrder: SellOrderEvent) =>
        updatedOrder.copySellOrderEventVars(order)
    }

    (order, updatedOrder) match {
      case (order: LimitOrderEvent, updatedOrder: LimitOrderEvent) =>
        updatedOrder.copyLimitOrderEventVars(order)
      case _ => None
    }

    updatedOrder
  }

  def generateOrderId: OrderId = OrderId(UUID.randomUUID.toString)

  def getOppositeSide(side: OrderSide): OrderSide = side match {
    case OrderSide.buy  => OrderSide.sell
    case OrderSide.sell => OrderSide.buy
  }

  def orderEventToSealedOneOf(order: OrderEvent): Order = {
    val orderMessage = order match {
      case order: BuyLimitOrder   => OrderMessage().withBuyLimitOrder(order)
      case order: SellLimitOrder  => OrderMessage().withSellLimitOrder(order)
      case order: BuyMarketOrder  => OrderMessage().withBuyMarketOrder(order)
      case order: SellMarketOrder => OrderMessage().withSellMarketOrder(order)
    }

    orderMessage.toOrder
  }

  def orderEventFromSealedOneOf(order: Order): Option[OrderEvent] = {
    if (order.asMessage.sealedValue.isBuyLimitOrder) {
      order.asMessage.sealedValue.buyLimitOrder
    } else if (order.asMessage.sealedValue.isBuyMarketOrder) {
      order.asMessage.sealedValue.buyMarketOrder
    } else if (order.asMessage.sealedValue.isSellLimitOrder) {
      order.asMessage.sealedValue.sellLimitOrder
    } else if (order.asMessage.sealedValue.isSellMarketOrder) {
      order.asMessage.sealedValue.sellMarketOrder
    } else {
      None
    }
  }

  def openOrder(order: LimitOrderEvent): LimitOrderEvent = {
    val updatedOrder = order match {
      case order: BuyLimitOrder =>
        order.update(_.orderStatus := OrderStatus.open)
      case order: SellLimitOrder =>
        order.update(_.orderStatus := OrderStatus.open)
    }

    copyVars(order, updatedOrder)
  }

  def rejectOrder[A <: OrderEvent](order: A, rejectReason: RejectReason): A = {
    val updatedOrder = order match {
      case order: BuyLimitOrder =>
        order.update(
          _.rejectReason := rejectReason,
          _.orderStatus := OrderStatus.rejected
        )
      case order: BuyMarketOrder =>
        order.update(
          _.rejectReason := rejectReason,
          _.orderStatus := OrderStatus.rejected
        )
      case order: SellLimitOrder =>
        order.update(
          _.rejectReason := rejectReason,
          _.orderStatus := OrderStatus.rejected
        )
      case order: SellMarketOrder =>
        order.update(
          _.rejectReason := rejectReason,
          _.orderStatus := OrderStatus.rejected
        )
    }
    copyVars(order, updatedOrder)
    updatedOrder.asInstanceOf[A]
  }

  def setOrderStatusToDone[A <: OrderEvent](order: A,
                                            doneReason: DoneReason): A = {
    val updatedOrder = order match {
      case order: BuyLimitOrder =>
        order.update(
          _.doneAt := Exchange.getSimulationMetadata.currentTimeInterval.startTime,
          _.doneReason := doneReason,
          _.orderStatus := OrderStatus.done
        )
      case order: BuyMarketOrder =>
        order.update(
          _.doneAt := Exchange.getSimulationMetadata.currentTimeInterval.startTime,
          _.doneReason := doneReason,
          _.orderStatus := OrderStatus.done
        )
      case order: SellLimitOrder =>
        order.update(
          _.doneAt := Exchange.getSimulationMetadata.currentTimeInterval.startTime,
          _.doneReason := doneReason,
          _.orderStatus := OrderStatus.done
        )
      case order: SellMarketOrder =>
        order.update(
          _.doneAt := Exchange.getSimulationMetadata.currentTimeInterval.startTime,
          _.doneReason := doneReason,
          _.orderStatus := OrderStatus.done
        )
    }
    copyVars(order, updatedOrder)
    updatedOrder.asInstanceOf[A]
  }
}

package co.firstorderlabs.fakebase

import java.util.UUID

import co.firstorderlabs.fakebase.protos.fakebase._
import co.firstorderlabs.fakebase.types.Types.OrderId
import co.firstorderlabs.fakebase.types.Events.{
  BuyOrderEvent,
  SellOrderEvent,
  SpecifiesFunds,
  SpecifiesSize,
  LimitOrderEvent,
  OrderEvent}

object OrderUtils {
  def addMatchToOrder[A <: OrderEvent](order: A, matchEvent: Match): A = {
    val matchEvents = order.matchEvents.get.addMatchEvents(matchEvent)
    val updatedOrder = order match {
      case order: BuyLimitOrder =>
        order.update(_.matchEvents := matchEvents)
      case order: BuyMarketOrder =>
        order.update(_.matchEvents := matchEvents)
      case order: SellLimitOrder =>
        order.update(_.matchEvents := matchEvents)
      case order: SellMarketOrder =>
        order.update(_.matchEvents := matchEvents)
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
            Exchange.simulationMetadata.get.currentTimeInterval.endTime
          )
    }

  def copyVars[A <: OrderEvent](order: A, updatedOrder: A): A = {
    (order, updatedOrder) match {
      case (order: SpecifiesFunds, updatedOrder: SpecifiesFunds) => updatedOrder.copySpecifiesFundsVars(order)
      case (order: SpecifiesSize, updatedOrder: SpecifiesSize) => updatedOrder.copySpecifiesSizeVars(order)
    }

    (order, updatedOrder) match {
      case (order: BuyOrderEvent, updatedOrder: BuyOrderEvent) => updatedOrder.copyBuyOrderEventVars(order)
      case (order: SellOrderEvent, updatedOrder: SellOrderEvent) => updatedOrder.copySellOrderEventVars(order)
    }

    (order, updatedOrder) match {
      case (order: LimitOrderEvent, updatedOrder: LimitOrderEvent) => updatedOrder.copyLimitOrderEventVars(order)
      case _ => None
    }

    updatedOrder
  }

  def generateOrderId: OrderId = OrderId(UUID.randomUUID.toString)

  def getOrderSealedValue(order: OrderEvent): Order = {
    val orderMessage = order match {
      case order: BuyLimitOrder => OrderMessage().withBuyLimitOrder(order)
      case order: SellLimitOrder => OrderMessage().withSellLimitOrder(order)
      case order: BuyMarketOrder => OrderMessage().withBuyMarketOrder(order)
      case order: SellMarketOrder => OrderMessage().withSellMarketOrder(order)
    }

    orderMessage.toOrder
  }

  def openOrder(order: LimitOrderEvent): LimitOrderEvent = {
    val updatedOrder = order match {
      case order: BuyLimitOrder =>
        order.update(
          _.orderStatus := OrderStatus.open
        )
      case order: SellLimitOrder =>
        order.update(
          _.orderStatus := OrderStatus.open
        )
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

  def setOrderStatusToDone[A <: OrderEvent](order: A, doneReason: DoneReason): A = {
    val updatedOrder = order match {
      case order: BuyLimitOrder =>
        order.update(
          _.doneAt := Exchange.simulationMetadata.get.currentTimeInterval.startTime,
          _.doneReason := doneReason,
          _.orderStatus := OrderStatus.done
        )
      case order: BuyMarketOrder =>
        order.update(
          _.doneAt := Exchange.simulationMetadata.get.currentTimeInterval.startTime,
          _.doneReason := doneReason,
          _.orderStatus := OrderStatus.done
        )
      case order: SellLimitOrder =>
        order.update(
          _.doneAt := Exchange.simulationMetadata.get.currentTimeInterval.startTime,
          _.doneReason := doneReason,
          _.orderStatus := OrderStatus.done
        )
      case order: SellMarketOrder =>
        order.update(
          _.doneAt := Exchange.simulationMetadata.get.currentTimeInterval.startTime,
          _.doneReason := doneReason,
          _.orderStatus := OrderStatus.done
        )
    }
    copyVars(order, updatedOrder)
    updatedOrder.asInstanceOf[A]
  }
}

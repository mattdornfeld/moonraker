package co.firstorderlabs.fakebase

import java.util.UUID

import co.firstorderlabs.fakebase.protos.fakebase._
import co.firstorderlabs.fakebase.types.Types.OrderId
import co.firstorderlabs.fakebase.types.Events.{LimitOrderEvent, OrderEvent}

object OrderUtils {
    def cancellationFromOrder(order: LimitOrderEvent): Cancellation = {
      Cancellation(
            order.orderId,
            order.price,
            order.productId,
            order.side,
            order.remainingSize,
            Server.exchange.currentTimeInterval.endDt
          )
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
    order match {
      case order: BuyLimitOrder =>
        order.update(
          _.orderStatus := OrderStatus.open
        )
      case order: SellLimitOrder =>
        order.update(
          _.orderStatus := OrderStatus.open
        )
    }
  }

  def setOrderStatusToDone[A <: OrderEvent](order: A, doneReason: DoneReason): A = {
    val updatedOrder = order match {
      case order: BuyLimitOrder =>
        order.update(
          _.doneAt := Server.exchange.currentTimeInterval.startDt,
          _.doneReason := doneReason,
          _.orderStatus := OrderStatus.done
        )
      case order: BuyMarketOrder =>
        order.update(
          _.doneAt := Server.exchange.currentTimeInterval.startDt,
          _.doneReason := doneReason,
          _.orderStatus := OrderStatus.done
        )
      case order: SellLimitOrder =>
        order.update(
          _.doneAt := Server.exchange.currentTimeInterval.startDt,
          _.doneReason := doneReason,
          _.orderStatus := OrderStatus.done
        )
      case order: SellMarketOrder =>
        order.update(
          _.doneAt := Server.exchange.currentTimeInterval.startDt,
          _.doneReason := doneReason,
          _.orderStatus := OrderStatus.done
        )
    }
    updatedOrder.asInstanceOf[A]
  }
}

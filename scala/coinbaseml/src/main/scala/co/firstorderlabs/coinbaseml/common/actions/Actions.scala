package co.firstorderlabs.coinbaseml.common.actions.actionizers

import java.time.Duration

import co.firstorderlabs.coinbaseml.common.utils.Utils.getResultOptional
import co.firstorderlabs.coinbaseml.fakebase.Account
import co.firstorderlabs.common.currency.Configs.ProductPrice
import co.firstorderlabs.common.currency.Configs.ProductPrice.{ProductVolume, QuoteVolume}
import co.firstorderlabs.common.protos.events.{BuyLimitOrder, Order, OrderSide, SellLimitOrder}
import co.firstorderlabs.common.protos.fakebase.{BuyLimitOrderRequest, BuyMarketOrderRequest, SellLimitOrderRequest, SellMarketOrderRequest}
import co.firstorderlabs.common.types.Events
import co.firstorderlabs.common.types.Events.OrderEvent
import co.firstorderlabs.common.types.Types.SimulationId
import scalapb.GeneratedMessage
import scalapb.lenses.Updatable

import scala.concurrent.Future

object Actions {
  sealed trait Action {
    def execute: Option[OrderEvent]
  }

  final case class LimitOrderTransaction(
      price: ProductPrice,
      size: ProductVolume,
      side: OrderSide,
      timeToLive: Duration,
      simulationId: SimulationId,
      postOnly: Boolean = false
  ) extends Action {
    override def execute: Option[OrderEvent] = {
      val order: Future[GeneratedMessage with Order.NonEmpty with Updatable[
        _ >: BuyLimitOrder with SellLimitOrder <: GeneratedMessage with Order.NonEmpty with Updatable[
          _ >: BuyLimitOrder with SellLimitOrder
        ] with Events.LimitOrderEvent
      ] with Events.LimitOrderEvent] = side match {
        case OrderSide.buy => {
          val buyLimitOrderRequest = BuyLimitOrderRequest(
            price,
            ProductPrice.productId,
            size,
            postOnly,
            Some(timeToLive),
            Some(simulationId),
          )
          Account.placeBuyLimitOrder(buyLimitOrderRequest)
        }
        case OrderSide.sell => {
          val sellLimitOrderRequest = SellLimitOrderRequest(
            price,
            ProductPrice.productId,
            size,
            postOnly,
            Some(timeToLive),
            Some(simulationId),
          )
          Account.placeSellLimitOrder(sellLimitOrderRequest)
        }
      }

      getResultOptional(order)
    }
  }

  final case class BuyMarketOrderTransaction(funds: QuoteVolume, simulationId: SimulationId) extends Action {
    override def execute: Option[OrderEvent] =
      getResultOptional(
        Account.placeBuyMarketOrder(
          BuyMarketOrderRequest(funds, ProductPrice.productId, Some(simulationId))
        )
      )
  }

  final class NoTransaction extends Action {
    override def execute: Option[OrderEvent] = None
  }

  final case class SellMarketOrderTransaction(size: ProductVolume, simulationId: SimulationId) extends Action {
    override def execute: Option[OrderEvent] =
      getResultOptional(
        Account.placeSellMarketOrder(
          SellMarketOrderRequest(ProductPrice.productId, size, Some(simulationId))
        )
      )
  }
}

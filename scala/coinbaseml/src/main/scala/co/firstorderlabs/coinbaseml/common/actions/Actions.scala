package co.firstorderlabs.coinbaseml.common.actions.actionizers

import java.time.Duration

import co.firstorderlabs.coinbaseml.common.utils.Utils.getResultOptional
import co.firstorderlabs.coinbaseml.fakebase.Account
import co.firstorderlabs.coinbaseml.fakebase.protos.fakebase.{BuyLimitOrderRequest, BuyMarketOrderRequest, SellLimitOrderRequest, SellMarketOrderRequest}
import co.firstorderlabs.common.currency.Configs.ProductPrice
import co.firstorderlabs.common.currency.Configs.ProductPrice.{ProductVolume, QuoteVolume}
import co.firstorderlabs.common.protos.events.{BuyLimitOrder, Order, OrderSide, SellLimitOrder}
import co.firstorderlabs.common.types.Events
import co.firstorderlabs.common.types.Events.OrderEvent
import scalapb.GeneratedMessage
import scalapb.lenses.Updatable

import scala.concurrent.Future

object Actions {
  trait Action {
    def execute: Option[OrderEvent]
  }

  case class LimitOrderTransaction(
      price: ProductPrice,
      size: ProductVolume,
      side: OrderSide,
      timeToLive: Duration,
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
            Some(timeToLive)
          )
          Account.placeBuyLimitOrder(buyLimitOrderRequest)
        }
        case OrderSide.sell => {
          val sellLimitOrderRequest = SellLimitOrderRequest(
            price,
            ProductPrice.productId,
            size,
            postOnly,
            Some(timeToLive)
          )
          Account.placeSellLimitOrder(sellLimitOrderRequest)
        }
      }

      getResultOptional(order)
    }
  }

  case class BuyMarketOrderTransaction(funds: QuoteVolume) extends Action {
    override def execute: Option[OrderEvent] =
      getResultOptional(
        Account.placeBuyMarketOrder(
          BuyMarketOrderRequest(funds, ProductPrice.productId)
        )
      )
  }

  class NoTransaction extends Action {
    override def execute: Option[OrderEvent] = None
  }

  case class SellMarketOrderTransaction(size: ProductVolume) extends Action {
    override def execute: Option[OrderEvent] =
      getResultOptional(
        Account.placeSellMarketOrder(
          SellMarketOrderRequest(ProductPrice.productId, size)
        )
      )
  }
}

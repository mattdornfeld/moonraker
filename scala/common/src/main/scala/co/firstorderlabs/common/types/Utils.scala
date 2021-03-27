package co.firstorderlabs.common.types

import co.firstorderlabs.common.protos.events.OrderSide

object Utils {
  implicit class OptionUtils[A](a: A) {
    def some: Option[A] = Some(a)
  }

  implicit class OrderSideUtils(side: OrderSide.Recognized) {
    def getOppositeSide: OrderSide.Recognized =
      if (side.isbuy) OrderSide.sell else OrderSide.buy
  }
}

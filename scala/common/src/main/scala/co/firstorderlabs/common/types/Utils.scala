package co.firstorderlabs.common.types

import co.firstorderlabs.common.protos.events.OrderSide

object Utils {
  implicit class OrderSideUtils(side: OrderSide.Recognized) {
    def getOppositeSide: OrderSide =
      if (side.isbuy) OrderSide.sell else OrderSide.buy
  }
}

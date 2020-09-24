package co.firstorderlabs.common.utils

import co.firstorderlabs.fakebase.{Account, Exchange}
import co.firstorderlabs.fakebase.TestData.OrdersData
import co.firstorderlabs.fakebase.TestData.RequestsData.buyMarketOrderRequest
import co.firstorderlabs.fakebase.currency.Configs.ProductPrice
import co.firstorderlabs.fakebase.currency.Price.BtcUsdPrice.ProductVolume
import co.firstorderlabs.fakebase.protos.fakebase.StepRequest
import org.scalactic.TolerantNumerics

object TestUtils {
  implicit val doubleEquality = TolerantNumerics.tolerantDoubleEquality(1e-10)

  def advanceExchange: Unit = {
    Exchange step StepRequest(
      insertOrders = OrdersData.insertSellOrders(
        new ProductPrice(Right("100.00")),
        new ProductVolume(Right("0.5"))
      ) ++ OrdersData.insertBuyOrders(new ProductPrice(Right("100.00")))
    )

    Account.placeBuyMarketOrder(buyMarketOrderRequest)

    Exchange step StepRequest(
      insertOrders = OrdersData.insertSellOrders(
        new ProductPrice(Right("100.00")),
        new ProductVolume(Right("0.5"))
      ) ++ OrdersData.insertBuyOrders(new ProductPrice(Right("100.00")))
    )

    Account.placeBuyMarketOrder(buyMarketOrderRequest)
  }
}

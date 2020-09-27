package co.firstorderlabs.common.utils

import co.firstorderlabs.fakebase.{Account, Exchange}
import co.firstorderlabs.fakebase.TestData.OrdersData
import co.firstorderlabs.fakebase.TestData.RequestsData.buyMarketOrderRequest
import co.firstorderlabs.fakebase.currency.Configs.ProductPrice
import co.firstorderlabs.fakebase.currency.Price.BtcUsdPrice.ProductVolume
import co.firstorderlabs.fakebase.protos.fakebase.StepRequest
import org.scalactic.TolerantNumerics

import scala.math.pow

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

  def mean(x: List[Double]): Double =
    if (x.size > 0)
      x.sum / x.size
    else 0.0

  def std(x: List[Double]): Double = {
    val mu = mean(x)
    val variance = x.map(item => pow(item - mu, 2)).sum / (x.size - 1)

    if (variance > 0) pow(variance, 0.5) else 0.0
  }

}

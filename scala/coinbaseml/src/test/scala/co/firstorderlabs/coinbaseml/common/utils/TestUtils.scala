package co.firstorderlabs.coinbaseml.common.utils

import co.firstorderlabs.coinbaseml.fakebase.TestData.OrdersData
import co.firstorderlabs.coinbaseml.fakebase.TestData.RequestsData.buyMarketOrderRequest
import co.firstorderlabs.coinbaseml.fakebase.protos.fakebase.StepRequest
import co.firstorderlabs.coinbaseml.fakebase.{Account, Exchange}
import co.firstorderlabs.common.currency.Configs.ProductPrice
import co.firstorderlabs.common.currency.Price.BtcUsdPrice.ProductVolume
import co.firstorderlabs.common.protos.events.OrderSide
import org.scalactic.TolerantNumerics

import scala.math.pow

object TestUtils {
  implicit val doubleEquality = TolerantNumerics.tolerantDoubleEquality(1e-10)

  implicit class OrderSideUtils(orderside: OrderSide) {
    def getOppositeSide: OrderSide = {
      orderside match {
        case OrderSide.buy => OrderSide.sell
        case OrderSide.sell => OrderSide.buy
      }
    }
  }

  implicit class SeqUtils[A](seq: Seq[A]) {
    def containsOnly(value: A): Boolean = {
      seq.forall(_ == value)
    }

    def dropIndices(indices: Int*): Seq[A] = {
      seq.zipWithIndex
        .filter(item => !indices.contains(item._2))
        .map(_._1)
    }

    def dropSlice(left: Int, right: Int): Seq[A] = {
      seq.zipWithIndex
        .filter(item => item._2 < left || item._2 > right - 1)
        .map(_._1)
    }
  }

  def advanceExchange: Unit = {
    Exchange step StepRequest(
      insertOrders = OrdersData.insertSellOrders(
        new ProductPrice(Right("100.00")),
        new ProductVolume(Right("0.5"))
      ) ++ OrdersData.insertBuyOrders(new ProductPrice(Right("100.00")))
    )
  }

  def advanceExchangeAndPlaceOrders: Unit = {
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

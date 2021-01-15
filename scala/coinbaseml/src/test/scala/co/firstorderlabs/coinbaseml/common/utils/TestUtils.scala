package co.firstorderlabs.coinbaseml.common.utils

import co.firstorderlabs.coinbaseml.fakebase.TestData.OrdersData
import co.firstorderlabs.coinbaseml.fakebase.TestData.RequestsData.buyMarketOrderRequest
import co.firstorderlabs.coinbaseml.fakebase.{
  Account,
  Exchange,
  SimulationMetadata
}
import co.firstorderlabs.common.currency.Configs.ProductPrice
import co.firstorderlabs.common.currency.Configs.ProductPrice.ProductVolume
import co.firstorderlabs.common.protos.events.OrderSide
import co.firstorderlabs.common.protos.fakebase.StepRequest
import co.firstorderlabs.common.types.Types.SimulationId
import org.scalactic.TolerantNumerics

import scala.annotation.tailrec
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.math.pow

object TestUtils {
  implicit val doubleEquality = TolerantNumerics.tolerantDoubleEquality(1e-10)

  implicit class FutureUtils[A](future: Future[A]) {
    def await(duration: Duration): Unit = Await.ready(future, duration)
  }

  implicit class OrderSideUtils(orderside: OrderSide) {
    def getOppositeSide: OrderSide = {
      orderside match {
        case OrderSide.buy  => OrderSide.sell
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

  def advanceExchange(implicit simulationMetadata: SimulationMetadata): Unit = {
    Exchange step StepRequest(
      insertOrders = OrdersData.insertSellOrders(
        new ProductPrice(Right("100.00")),
        new ProductVolume(Right("0.5"))
      ) ++ OrdersData.insertBuyOrders(new ProductPrice(Right("100.00"))),
      simulationId = Some(simulationMetadata.simulationId)
    )
  }

  def advanceExchangeAndPlaceOrders(implicit
      simulationMetadata: SimulationMetadata
  ): Unit = {
    Exchange step StepRequest(
      insertOrders = OrdersData.insertSellOrders(
        new ProductPrice(Right("100.00")),
        new ProductVolume(Right("0.5"))
      ) ++ OrdersData.insertBuyOrders(new ProductPrice(Right("100.00"))),
      simulationId = Some(simulationMetadata.simulationId)
    )

    Account.placeBuyMarketOrder(buyMarketOrderRequest(simulationMetadata.simulationId))

    Exchange step StepRequest(
      insertOrders = OrdersData.insertSellOrders(
        new ProductPrice(Right("100.00")),
        new ProductVolume(Right("0.5"))
      ) ++ OrdersData.insertBuyOrders(new ProductPrice(Right("100.00"))),
      simulationId = Some(simulationMetadata.simulationId)
    )

    Account.placeBuyMarketOrder(buyMarketOrderRequest(simulationMetadata.simulationId))
  }

  def buildStepRequest(simulationId: SimulationId): StepRequest =
    StepRequest(simulationId = Some(simulationId))

  def mean(x: List[Double]): Double =
    if (x.size > 0)
      x.sum / x.size
    else 0.0

  def std(x: List[Double]): Double = {
    val mu = mean(x)
    val variance = x.map(item => pow(item - mu, 2)).sum / (x.size - 1)

    if (variance > 0) pow(variance, 0.5) else 0.0
  }

  @tailrec
  final def waitUntil(condition: () => Boolean): Unit = {
    if (!condition()) {
      Thread.sleep(5)
      waitUntil(condition)
    }
  }

}

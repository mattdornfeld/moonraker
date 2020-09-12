package co.firstorderlabs.fakebase.types

import java.time.{Duration, Instant}

import co.firstorderlabs.fakebase.protos.fakebase.Currency
import scalapb.TypeMapper

object Types {
  case class OrderId(orderId: String) extends AnyVal
  case class OrderRequestId(orderRequestId: String) extends AnyVal
  case class TradeId(tradeId: Long) extends AnyVal

  case class ProductId(productCurrency: Currency, quoteCurrency: Currency) {
    override def toString: String =
      productCurrency.toString + "-" + quoteCurrency.toString
  }

  case class TimeInterval(startTime: Instant, endTime: Instant) {
    def +(duration: Duration): TimeInterval = {
      val startDt = this.startTime.plus(duration)
      val endDt = this.endTime.plus(duration)
      TimeInterval(startDt, endDt)
    }

    def -(duration: Duration): TimeInterval = {
      val startDt = this.startTime.minus(duration)
      val endDt = this.endTime.minus(duration)
      TimeInterval(startDt, endDt)
    }

    def contains(instant: Instant): Boolean =
      (instant.compareTo(startTime) >= 0
        && instant.compareTo(endTime) < 0)
  }

  object OrderId {
    implicit val typeMapper =
      TypeMapper[String, OrderId](value => OrderId(value))(orderId =>
        orderId.orderId
      )
  }

  object OrderRequestId {
    implicit val typeMapper = TypeMapper[String, OrderRequestId](value =>
      OrderRequestId(value)
    )(orderRequestId => orderRequestId.orderRequestId)
  }

  object ProductId {
    implicit val typeMapper = TypeMapper[String, ProductId](value =>
      ProductId.fromString(value)
    )(productId => productId.toString)

    def fromString(productId: String): ProductId = {
      val productCurrency = Currency
        .fromName(
          if (productId.contains("-")) productId.split("-").head
          else ""
        )
        .getOrElse(Currency.QUATLOO)

      val quoteCurrency = Currency
        .fromName(
          if (productId.contains("-")) productId.split("-").tail.head
          else ""
        )
        .getOrElse(Currency.QUATLOO)

      ProductId(productCurrency, quoteCurrency)
    }
  }

  object TradeId {
    implicit val typeMapper =
      TypeMapper[Long, TradeId](value => TradeId(value))(tradeId =>
        tradeId.tradeId
      )
  }
}

package co.firstorderlabs.fakebase.types

import java.time.{Duration, Instant}

import co.firstorderlabs.fakebase.protos.fakebase.Currency

import scalapb.TypeMapper

object Types {
  case class OrderId(orderId: String) extends AnyVal
  case class Datetime(instant: Instant) extends AnyVal {
    override def toString: String = instant.toString
  }
  case class OrderRequestId(orderRequestId: String) extends AnyVal
  case class TradeId(tradeId: Long) extends AnyVal

  case class ProductId(productCurrency: Currency, quoteCurrency: Currency) {
    override def toString: String =
      productCurrency.toString + "-" + quoteCurrency.toString
  }

  case class TimeInterval(startTime: Datetime, endTime: Datetime) {
    def +(duration: Duration): TimeInterval = {
      val startDt = Datetime(this.startTime.instant.plus(duration))
      val endDt = Datetime(this.endTime.instant.plus(duration))
      TimeInterval(startDt, endDt)
    }

    def -(duration: Duration): TimeInterval = {
      val startDt = Datetime(this.startTime.instant.minus(duration))
      val endDt = Datetime(this.endTime.instant.minus(duration))
      TimeInterval(startDt, endDt)
    }
  }

  object Datetime {
    implicit val typeMapper = TypeMapper[String, Datetime](
      value => Datetime(parse(value))
    )(datetime => datetime.instant.toString)

    def parse(timestamp: String) = {
      if (timestamp.length == 0)
        Instant.now
      else
        Instant.parse(timestamp)
    }
  }

  object OrderId {
    implicit val typeMapper = TypeMapper[String, OrderId](
      value => OrderId(value)
    )(orderId => orderId.orderId)
  }

  object OrderRequestId {
    implicit val typeMapper = TypeMapper[String, OrderRequestId](
      value => OrderRequestId(value)
    )(orderRequestId => orderRequestId.orderRequestId)
  }

  object ProductId {
    implicit val typeMapper = TypeMapper[String, ProductId](
      value => ProductId.fromString(value)
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
    implicit val typeMapper = TypeMapper[Long, TradeId](
      value => TradeId(value)
    )(tradeId => tradeId.tradeId)
  }
}

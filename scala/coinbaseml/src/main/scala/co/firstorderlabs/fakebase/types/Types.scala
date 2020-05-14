package co.firstorderlabs.fakebase.types

import java.time.{Duration, Instant}

import scalapb.TypeMapper

object Types {
  case class Currency(currency: String) extends AnyVal
  case class OrderId(orderId: String) extends AnyVal
  case class Datetime(instant: Instant) extends AnyVal {
    override def toString: String = instant.toString
  }
  case class OrderRequestId(orderRequestId: String) extends AnyVal
  case class TradeId(tradeId: Long) extends AnyVal

  case class ProductId(productCurrency: Currency, quoteCurrency: Currency) {
    override def toString: String =
      productCurrency.currency + "-" + quoteCurrency.currency
  }

  case class TimeInterval(startDt: Datetime, endDt: Datetime) {
    def +(duration: Duration): TimeInterval = {
      val startDt = Datetime(this.startDt.instant.plus(duration))
      val endDt = Datetime(this.endDt.instant.plus(duration))
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
      val productCurrency = if (productId.length > 0) Currency(productId.split("-")(0)) else Currency("")
      val quoteCurrency = if (productId.length > 0) Currency(productId.split("-")(1)) else Currency("")
      ProductId(productCurrency, quoteCurrency)
    }
  }

  object TradeId {
    implicit val typeMapper = TypeMapper[Long, TradeId](value => TradeId(value))(tradeId => tradeId.tradeId)
  }

}

package co.firstorderlabs.fakebase

import java.time.{Duration, Instant}

import co.firstorderlabs.fakebase.currency.Configs.ProductPrice
import co.firstorderlabs.fakebase.currency.Configs.ProductPrice.{ProductVolume, QuoteVolume}
import co.firstorderlabs.fakebase.protos.fakebase._
import co.firstorderlabs.fakebase.types.Types.Datetime

object TestData {
    Exchange.simulationMetadata = Some(SimulationMetadata(
      Datetime(Instant.parse("2020-01-18T17:00:00Z")),
      Datetime(Instant.parse("2020-01-18T17:10:00Z")),
      Duration.ofSeconds(30),
      0,
      new ProductVolume(Right("1.000000")),
      new QuoteVolume(Right("1000.00"))
    ))

  val lowerOrder = BuyLimitOrder(
    OrderUtils.generateOrderId,
    OrderStatus.open,
    new ProductPrice(Right("1000.00")),
    ProductPrice.productId,
    OrderSide.buy,
    new ProductVolume(Right("1.000000")),
    Datetime(Instant.now)
  )

  val higherOrder = BuyLimitOrder(
    OrderUtils.generateOrderId,
    OrderStatus.open,
    new ProductPrice(Right("2000.00")),
    ProductPrice.productId,
    OrderSide.buy,
    new ProductVolume(Right("1.000000")),
    Datetime(Instant.now)
  )

  val takerSellOrder = SellLimitOrder(
    OrderUtils.generateOrderId,
    OrderStatus.open,
    new ProductPrice(Right("2000.00")),
    ProductPrice.productId,
    OrderSide.sell,
    new ProductVolume(Right("1.000000")),
    Datetime(Instant.now)
  )

  val cancellation = OrderUtils.cancellationFromOrder(higherOrder)
}

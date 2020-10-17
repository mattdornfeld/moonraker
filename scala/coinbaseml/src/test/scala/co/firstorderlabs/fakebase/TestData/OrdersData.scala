package co.firstorderlabs.fakebase.TestData

import java.time.Instant

import co.firstorderlabs.fakebase.currency.Configs.ProductPrice
import co.firstorderlabs.fakebase.currency.Configs.ProductPrice.ProductVolume
import co.firstorderlabs.fakebase.protos.fakebase._
import co.firstorderlabs.fakebase.utils.OrderUtils
import co.firstorderlabs.fakebase.{Exchange, TestUtils}

object OrdersData {
  val priceDelta = new ProductPrice(Right("1.00"))

  def insertBuyOrders(
    maxPrice: ProductPrice,
    productVolume: ProductVolume = new ProductVolume(Right("1.000000")),
    numOrders: Int = 100,
  ): List[Order] = TestUtils.generateOrdersForRangeOfPrices(
    priceDelta,
    maxPrice - priceDelta / Right(1.0 / numOrders),
    maxPrice,
    OrderSide.buy,
    productVolume,
    Exchange.getSimulationMetadata.currentTimeInterval.endTime
  )

  def insertSellOrders(
    minPrice: ProductPrice,
    productVolume: ProductVolume = new ProductVolume(Right("1.000000")),
    numOrders: Int = 100,
  ): List[Order] = TestUtils.generateOrdersForRangeOfPrices(
    priceDelta,
    minPrice,
    minPrice + priceDelta / Right(1.0 / numOrders),
    OrderSide.sell,
    productVolume,
    Exchange.getSimulationMetadata.currentTimeInterval.endTime
  )

  val lowerOrder = BuyLimitOrder(
    OrderUtils.generateOrderId,
    OrderStatus.open,
    new ProductPrice(Right("1000.00")),
    ProductPrice.productId,
    OrderSide.buy,
    new ProductVolume(Right("1.000000")),
    Instant.now
  )

  val higherOrder = BuyLimitOrder(
    OrderUtils.generateOrderId,
    OrderStatus.open,
    new ProductPrice(Right("2000.00")),
    ProductPrice.productId,
    OrderSide.buy,
    new ProductVolume(Right("1.000000")),
    Instant.now
  )

  val takerSellOrder = SellLimitOrder(
    OrderUtils.generateOrderId,
    OrderStatus.open,
    new ProductPrice(Right("2000.00")),
    ProductPrice.productId,
    OrderSide.sell,
    new ProductVolume(Right("1.000000")),
    Instant.now
  )

  val cancellation = OrderUtils.cancellationFromOrder(higherOrder)
}

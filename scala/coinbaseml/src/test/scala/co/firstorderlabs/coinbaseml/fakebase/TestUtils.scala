package co.firstorderlabs.coinbaseml.fakebase

import java.time.Instant

import co.firstorderlabs.coinbaseml.fakebase.utils.OrderUtils
import co.firstorderlabs.common.currency.Configs.ProductPrice
import co.firstorderlabs.common.currency.Configs.ProductPrice.ProductVolume
import co.firstorderlabs.common.protos.events._

object TestUtils {
  def generateOrdersForRangeOfPrices(priceDelta: ProductPrice,
                                     priceMin: ProductPrice,
                                     priceMax: ProductPrice,
                                     side: OrderSide,
                                     size: ProductVolume,
                                     time: Instant): List[Order] = {
    generatePrices(priceDelta, priceMin, priceMax)
      .map(
        price =>
          side match {
            case OrderSide.buy =>
              BuyLimitOrder(
                OrderUtils.generateOrderId,
                OrderStatus.received,
                price,
                ProductPrice.productId,
                side,
                size,
                time
              )
            case OrderSide.sell =>
              SellLimitOrder(
                OrderUtils.generateOrderId,
                OrderStatus.received,
                price,
                ProductPrice.productId,
                side,
                size,
                time
              )
        }
      )
      .map(OrderUtils.orderEventToSealedOneOf)
      .toList
  }

  def generatePrices(priceDelta: ProductPrice,
                     priceMin: ProductPrice,
                     priceMax: ProductPrice): Iterable[ProductPrice] = {
    (BigDecimal(priceMin.amount) until BigDecimal(priceMax.amount) by BigDecimal(
      priceDelta.amount
    )).map(amount => new ProductPrice(Right(amount.toString)))
  }

  def getOrderBookPrices(depth: Int)(implicit orderBookState: OrderBookState): Seq[ProductPrice] = {
    val orderBookSeq = OrderBook.iterator.toSeq
    orderBookState.side match {
      case OrderSide.buy => orderBookSeq.reverse.take(depth).map(item => item._2.price)
      case OrderSide.sell => orderBookSeq.take(depth).map(item => item._2.price)
    }
  }
}

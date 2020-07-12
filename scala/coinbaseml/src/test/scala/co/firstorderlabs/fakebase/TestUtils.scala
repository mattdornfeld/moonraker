package co.firstorderlabs.fakebase

import co.firstorderlabs.fakebase.currency.Configs.ProductPrice.ProductVolume
import co.firstorderlabs.fakebase.protos.fakebase._
import co.firstorderlabs.fakebase.currency.Configs.ProductPrice
import co.firstorderlabs.fakebase.types.Types.Datetime

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

object TestUtils {
  def generateOrdersForRangeOfPrices(priceDelta: ProductPrice,
                                     priceMin: ProductPrice,
                                     priceMax: ProductPrice,
                                     side: OrderSide,
                                     size: ProductVolume,
                                     time: Datetime): List[Order] = {
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

  def getOrderBookPrices(depth: Int, orderSide: OrderSide): Seq[ProductPrice] = {
    val orderBookSeq = Exchange.getOrderBook(orderSide).iterator.toSeq
    orderSide match {
      case OrderSide.buy => orderBookSeq.reverse.take(depth).map(item => item._2.price)
      case OrderSide.sell => orderBookSeq.take(depth).map(item => item._2.price)
    }
  }

  def getResult[A](future: Future[A]): A = {
    Await.result(future, Duration.MinusInf)
  }
}

package co.firstorderlabs.fakebase

import co.firstorderlabs.fakebase.TestData.OrdersData._
import co.firstorderlabs.fakebase.TestData.RequestsData
import org.scalatest.funspec.AnyFunSpec

class OrderBookTest extends AnyFunSpec {

  Configs.testMode = true
  val orderBook = new OrderBook
  Exchange.start(RequestsData.simulationStartRequest)

  describe("OrderBook") {
    it("should do the following when empty") {
      assert(orderBook.minOrder.isEmpty)
      assert(orderBook.maxOrder.isEmpty)
      assert(orderBook.minPrice.isEmpty)
      assert(orderBook.maxPrice.isEmpty)
      assert(orderBook.isEmpty)
    }

    it("should be able to do the following when one order is added") {
      val orderBookKey = OrderBook.getOrderBookKey(lowerOrder)
      orderBook.update(orderBookKey, lowerOrder)
      assert(
        orderBook.getOrderByOrderBookKey(orderBookKey).get equalTo lowerOrder
      )
      assert(
        orderBook.getOrderByOrderId(lowerOrder.orderId).get equalTo lowerOrder
      )
      assert(orderBook.maxOrder.get equalTo lowerOrder)
      assert(orderBook.minOrder.get equalTo lowerOrder)
      assert(orderBook.maxPrice.get equalTo lowerOrder.price)
      assert(orderBook.minPrice.get equalTo lowerOrder.price)
      assert(!orderBook.isEmpty)
      assert(orderBook.removeByKey(orderBookKey).get equalTo lowerOrder)
    }

    it("should be able to do the following when two orders are added") {
      orderBook.update(OrderBook.getOrderBookKey(lowerOrder), lowerOrder)
      orderBook.update(OrderBook.getOrderBookKey(higherOrder), higherOrder)
      assert(orderBook.maxOrder.get equalTo higherOrder)
      assert(orderBook.minOrder.get equalTo lowerOrder)
      assert(orderBook.maxPrice.get equalTo higherOrder.price)
      assert(orderBook.minPrice.get equalTo lowerOrder.price)
      assert(!orderBook.isEmpty)
      assert(
        orderBook.removeByOrderId(lowerOrder.orderId).get equalTo lowerOrder
      )
      assert(
        orderBook.removeByOrderId(higherOrder.orderId).get equalTo higherOrder
      )
    }
  }
}

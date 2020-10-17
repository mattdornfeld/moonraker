package co.firstorderlabs.fakebase

import java.time.Instant

import co.firstorderlabs.common.utils.TestUtils.SeqUtils
import co.firstorderlabs.fakebase.TestData.OrdersData._
import co.firstorderlabs.fakebase.TestData.RequestsData
import co.firstorderlabs.fakebase.currency.Configs.ProductPrice
import co.firstorderlabs.fakebase.currency.Price.BtcUsdPrice.ProductVolume
import co.firstorderlabs.fakebase.protos.fakebase.{BuyLimitOrder, OrderSide}
import co.firstorderlabs.fakebase.utils.DuplicateKey
import org.scalatest.funspec.AnyFunSpec

class PriceGlobTest extends AnyFunSpec {
  describe("PriceGlob") {
    val expectedPrice = new ProductPrice(Right("1000.00"))
    Configs.testMode = true
    Exchange.start(RequestsData.simulationStartRequest)
    val productVolume = new ProductVolume(Right("1.00"))
    val testOrders = List(Instant.MIN, Instant.now, Instant.MAX).map { time =>
      new BuyLimitOrder(
        price = expectedPrice,
        productId = ProductPrice.productId,
        size = productVolume,
        side = OrderSide.buy,
        time = time
      )
    }

    it("The price property should equal the price passed in at construction") {
      val priceGlob = PriceGlob(expectedPrice)
      assert(expectedPrice equalTo priceGlob.price)
    }

    it("Newly created PriceGlobs are empty") {
      val priceGlob = PriceGlob(expectedPrice)
      assert(priceGlob.isEmpty)
      assert(priceGlob.oldestOrder.isEmpty)
    }

    it("Orders should be added and removed using the put and remove methods") {
      val priceGlob = PriceGlob(expectedPrice)
      val orderBookKey = lowerOrder.getOrderBookKey
      priceGlob.put(orderBookKey, lowerOrder)
      assert(lowerOrder equalTo priceGlob.get(orderBookKey).get)
      priceGlob.remove(orderBookKey)
      assert(priceGlob.get(orderBookKey).isEmpty)
    }

    it(
      "Trying to insert the same order twice throws a DuplicateKey exception"
    ) {
      val priceGlob = PriceGlob(expectedPrice)
      val orderBookKey = lowerOrder.getOrderBookKey
      priceGlob.put(orderBookKey, lowerOrder)
      assertThrows[DuplicateKey] { priceGlob.put(orderBookKey, lowerOrder) }
    }

    it("The oldest order is the first one added to the PriceGlob") {
      val priceGlob = PriceGlob(expectedPrice)
      testOrders.foreach(o => priceGlob.put(o.getOrderBookKey, o))
      assert(priceGlob.oldestOrder.get equalTo testOrders(0))
    }

    it("The PriceGlob can create an iterator which contains all orders") {
      val priceGlob = PriceGlob(expectedPrice)
      testOrders.foreach(o => priceGlob.put(o.getOrderBookKey, o))
      assert(
        priceGlob.iterator
          .zip(testOrders)
          .map(item => item._1._2 equalTo item._2)
          .forall(_ == true)
      )
    }

    it(
      "PriceGlob should support random removals of elements by their index key"
    ) {
      val priceGlob = PriceGlob(expectedPrice)
      testOrders.foreach(o => priceGlob.put(o.getOrderBookKey, o))
      val middleOrder = testOrders(1)
      priceGlob.remove(middleOrder.getOrderBookKey)
      assert(
        priceGlob.iterator
          .zip(testOrders.dropIndices(1))
          .map(item => item._1._2 equalTo item._2)
          .forall(_ == true)
      )
    }

    it("The aggregateVolume method should sum the volume in the PriceGlob") {
      val priceGlob = PriceGlob(expectedPrice)
      testOrders.foreach(o => priceGlob.put(o.getOrderBookKey, o))
      val expectedVolume = testOrders.map(_.size).reduce(_ + _)
      assert(expectedVolume equalTo priceGlob.aggregateVolume)
    }

    it("A cloned PriceGlob should be equal to the original.") {
      val priceGlob = PriceGlob(expectedPrice)
      testOrders.foreach(o => priceGlob.put(o.getOrderBookKey, o))
      priceGlob equalTo priceGlob.clone
    }
  }
}

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

    it(
      "The aggregateToMap method should return the same result before and after a snapshot is restored. This is a sign" +
        "that the restore functioned correctly."
    ) {
      val orderBook = new OrderBook
      orderBook.update(OrderBook.getOrderBookKey(lowerOrder), lowerOrder)
      orderBook.update(OrderBook.getOrderBookKey(higherOrder), higherOrder)
      val expectedAggregatedMap = orderBook.aggregateToMap(2)
      orderBook.restore(orderBook.createSnapshot)
      val aggregatedMap = orderBook.aggregateToMap(2)

      assert(expectedAggregatedMap == aggregatedMap)
    }
  }
}

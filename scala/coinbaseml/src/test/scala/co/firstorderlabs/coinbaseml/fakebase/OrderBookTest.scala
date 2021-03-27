package co.firstorderlabs.coinbaseml.fakebase

import co.firstorderlabs.coinbaseml.common.Configs.testMode

import java.time.Instant
import co.firstorderlabs.coinbaseml.common.utils.TestUtils.SeqUtils
import co.firstorderlabs.coinbaseml.common.utils.Utils.getResult
import co.firstorderlabs.coinbaseml.fakebase.TestData.OrdersData._
import co.firstorderlabs.coinbaseml.fakebase.TestData.RequestsData
import co.firstorderlabs.coinbaseml.fakebase.utils.DuplicateKey
import co.firstorderlabs.common.currency.Configs.ProductPrice
import co.firstorderlabs.common.currency.Configs.ProductPrice.ProductVolume
import co.firstorderlabs.common.protos.events.{BuyLimitOrder, OrderSide}
import org.scalatest.funspec.AnyFunSpec

class PriceGlobTest extends AnyFunSpec {
  describe("PriceGlob") {
    val expectedPrice = new ProductPrice(Right("1000.00"))
    testMode = true
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
      val orderBookKey = lowerOrder.orderBookKey
      priceGlob.put(orderBookKey, lowerOrder)
      assert(lowerOrder equalTo priceGlob.get(orderBookKey).get)
      priceGlob.remove(orderBookKey)
      assert(priceGlob.get(orderBookKey).isEmpty)
    }

    it(
      "Trying to insert the same order twice throws a DuplicateKey exception"
    ) {
      val priceGlob = PriceGlob(expectedPrice)
      val orderBookKey = lowerOrder.orderBookKey
      priceGlob.put(orderBookKey, lowerOrder)
      assertThrows[DuplicateKey] { priceGlob.put(orderBookKey, lowerOrder) }
    }

    it("The oldest order is the first one added to the PriceGlob") {
      val priceGlob = PriceGlob(expectedPrice)
      testOrders.foreach(o => priceGlob.put(o.orderBookKey, o))
      assert(priceGlob.oldestOrder.get equalTo testOrders(0))
    }

    it("The PriceGlob can create an iterator which contains all orders") {
      val priceGlob = PriceGlob(expectedPrice)
      testOrders.foreach(o => priceGlob.put(o.orderBookKey, o))
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
      testOrders.foreach(o => priceGlob.put(o.orderBookKey, o))
      val middleOrder = testOrders(1)
      priceGlob.remove(middleOrder.orderBookKey)
      assert(
        priceGlob.iterator
          .zip(testOrders.dropIndices(1))
          .map(item => item._1._2 equalTo item._2)
          .forall(_ == true)
      )
    }

    it("The aggregateVolume method should sum the volume in the PriceGlob") {
      val priceGlob = PriceGlob(expectedPrice)
      testOrders.foreach(o => priceGlob.put(o.orderBookKey, o))
      val expectedVolume = testOrders.map(_.size).reduce(_ + _)
      assert(expectedVolume equalTo priceGlob.aggregateVolume)
    }

    it("A cloned PriceGlob should be equal to the original.") {
      val priceGlob = PriceGlob(expectedPrice)
      testOrders.foreach(o => priceGlob.put(o.orderBookKey, o))
      priceGlob equalTo priceGlob.clone
    }
  }
}

class OrderBookTest extends AnyFunSpec {

  testMode = true
  val simulationId = getResult(Exchange.start(RequestsData.simulationStartRequest)).simulationId.get
  implicit val simulationMetadata = SimulationState.getSimulationMetadataOrFail(simulationId)

  describe("OrderBook") {
    it("should do the following when empty") {
      implicit val buyOrderBookState = BuyOrderBookState.create
      implicit val sellOrderBookState = SellOrderBookState.create
      assert(OrderBook.minOrder.isEmpty)
      assert(OrderBook.maxOrder.isEmpty)
      assert(OrderBook.minPrice.isEmpty)
      assert(OrderBook.maxPrice.isEmpty)
      assert(OrderBook.isEmpty(buyOrderBookState))
      assert(OrderBook.isEmpty(sellOrderBookState))
    }

    it("should be able to do the following when one order is added") {
      implicit val buyOrderBookState = BuyOrderBookState.create
      val orderBookKey = OrderBook.getOrderBookKey(lowerOrder)
      OrderBook.update(orderBookKey, lowerOrder)
      assert(
        OrderBook.getOrderByOrderBookKey(orderBookKey).get equalTo lowerOrder
      )
      assert(
        OrderBook.getOrderByOrderId(lowerOrder.orderId).get equalTo lowerOrder
      )
      assert(OrderBook.bestOrder.get equalTo lowerOrder)
      assert(OrderBook.bestPrice.get equalTo lowerOrder.price)
      assert(!OrderBook.isEmpty)
      assert(OrderBook.removeByKey(orderBookKey).get equalTo lowerOrder)
    }

    it("should be able to do the following when two orders are added") {
        implicit val buyOrderBookState = BuyOrderBookState.create
        OrderBook.update(OrderBook.getOrderBookKey(lowerOrder), lowerOrder)
        OrderBook.update(OrderBook.getOrderBookKey(higherOrder), higherOrder)
        assert(OrderBook.maxOrder.get equalTo higherOrder)
        assert(OrderBook.iterator.toList.head._2 equalTo lowerOrder)
        assert(OrderBook.maxPrice.get equalTo higherOrder.price)
        assert(OrderBook.iterator.toList.head._2.price equalTo lowerOrder.price)
        assert(!OrderBook.isEmpty)
        assert(
          OrderBook.removeByOrderId(lowerOrder.orderId).get equalTo lowerOrder
        )
        assert(
          OrderBook
            .removeByOrderId(higherOrder.orderId)
            .get equalTo higherOrder
        )
    }

    it(
      "The aggregateToMap method should return the same result before and after a snapshot is restored. This is a sign" +
        "that the restore functioned correctly."
    ) {
      val simulationId = getResult(Exchange.start(RequestsData.simulationStartRequest)).simulationId.get
      implicit val simulationMetadata = SimulationState.getSimulationMetadataOrFail(simulationId)
      val buyOrderBookState = SimulationState.getOrFail(simulationId).matchingEngineState.buyOrderBookState
      OrderBook.update(OrderBook.getOrderBookKey(lowerOrder), lowerOrder)(buyOrderBookState)
      OrderBook.update(OrderBook.getOrderBookKey(higherOrder), higherOrder)(buyOrderBookState)
      val expectedAggregatedMap = OrderBook.aggregateToMap(2)(buyOrderBookState)
      SimulationState.restore(simulationId)
      val restoredBuyOrderBookState = SimulationState.getOrFail(simulationId).matchingEngineState.buyOrderBookState
      val aggregatedMap = OrderBook.aggregateToMap(2)(restoredBuyOrderBookState)

      assert(expectedAggregatedMap == aggregatedMap)
    }
  }
}

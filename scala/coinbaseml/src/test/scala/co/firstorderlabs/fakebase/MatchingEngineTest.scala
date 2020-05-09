package co.firstorderlabs.fakebase

import co.firstorderlabs.fakebase.protos.fakebase._
import co.firstorderlabs.fakebase.types.Events.Event
import org.scalatest.funspec.AnyFunSpec

class MatchingEngineTest extends AnyFunSpec {
  val matchingEngine = new MatchingEngine
  val buyOrderEvents = List[Event](TestData.higherOrder, TestData.lowerOrder)
  val buyOrderEventsWithCancellation = List[Event](TestData.higherOrder, TestData.lowerOrder, TestData.cancellation)
  val buyOrderEventsAndTakerOrder = List[Event](TestData.higherOrder, TestData.lowerOrder, TestData.takerSellOrder)

  describe("MatchingEngine") {
    it("should do the following when buy orders are added") {
      matchingEngine.processEvents(buyOrderEvents)

      assert(matchingEngine.orderBooks(OrderSide.buy).maxOrder.get equalTo TestData.higherOrder)
      assert(matchingEngine.orderBooks(OrderSide.buy).minOrder.get equalTo TestData.lowerOrder)
      assert(matchingEngine.orderBooks(OrderSide.buy).maxPrice.get equalTo TestData.higherOrder.price)
      assert(matchingEngine.orderBooks(OrderSide.buy).minPrice.get equalTo TestData.lowerOrder.price)
      assert(matchingEngine.checkIsTaker(TestData.takerSellOrder))
    }

    it("should do the following when buy orders are added and one is cancelled") {
      matchingEngine.processEvents(buyOrderEventsWithCancellation)
      assert(matchingEngine.orderBooks(OrderSide.buy).maxOrder.get equalTo TestData.lowerOrder)
      assert(matchingEngine.orderBooks(OrderSide.buy).minOrder.get equalTo TestData.lowerOrder)
    }

    it("should do the following when buy orders are added and a sell order is matched") {
      matchingEngine.processEvents(buyOrderEventsAndTakerOrder)
      val matchEvent = matchingEngine.matches(0)
      assert(matchEvent.price equalTo TestData.higherOrder.price)
      assert(matchEvent.size equalTo TestData.higherOrder.size)
      assert(matchEvent.makerOrder.asMessage.getBuyLimitOrder equalTo OrderUtils.setOrderStatusToDone(TestData.higherOrder, DoneReason.filled))
      assert(matchEvent.takerOrder.asMessage.getSellLimitOrder equalTo OrderUtils.setOrderStatusToDone(TestData.takerSellOrder, DoneReason.filled))
    }
  }
}

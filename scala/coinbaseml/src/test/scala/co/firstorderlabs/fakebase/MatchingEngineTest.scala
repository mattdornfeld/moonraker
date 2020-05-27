package co.firstorderlabs.fakebase

import co.firstorderlabs.fakebase.protos.fakebase._
import co.firstorderlabs.fakebase.types.Events.Event
import org.scalatest.funspec.AnyFunSpec

class MatchingEngineTest extends AnyFunSpec {
  val buyOrderEvents = List[Event](TestData.higherOrder, TestData.lowerOrder)
  val buyOrderEventsWithCancellation = List[Event](TestData.higherOrder, TestData.lowerOrder, TestData.cancellation)
  val buyOrderEventsAndTakerOrder = List[Event](TestData.higherOrder, TestData.lowerOrder, TestData.takerSellOrder)

  describe("MatchingEngine") {
    it("should do the following when buy orders are added") {
      MatchingEngine.processEvents(buyOrderEvents)

      assert(MatchingEngine.orderBooks(OrderSide.buy).maxOrder.get equalTo TestData.higherOrder)
      assert(MatchingEngine.orderBooks(OrderSide.buy).minOrder.get equalTo TestData.lowerOrder)
      assert(MatchingEngine.orderBooks(OrderSide.buy).maxPrice.get equalTo TestData.higherOrder.price)
      assert(MatchingEngine.orderBooks(OrderSide.buy).minPrice.get equalTo TestData.lowerOrder.price)
      assert(MatchingEngine.checkIsTaker(TestData.takerSellOrder))
    }

    it("should do the following when buy orders are added and one is cancelled") {
      MatchingEngine.processEvents(buyOrderEventsWithCancellation)
      assert(MatchingEngine.orderBooks(OrderSide.buy).maxOrder.get equalTo TestData.lowerOrder)
      assert(MatchingEngine.orderBooks(OrderSide.buy).minOrder.get equalTo TestData.lowerOrder)
    }

    it("should do the following when buy orders are added and a sell order is matched") {
      MatchingEngine.processEvents(buyOrderEventsAndTakerOrder)
      val matchEvent = MatchingEngine.matches(0)
      assert(matchEvent.price equalTo TestData.higherOrder.price)
      assert(matchEvent.size equalTo TestData.higherOrder.size)
      assert(matchEvent.makerOrder.asMessage.getBuyLimitOrder equalTo OrderUtils.setOrderStatusToDone(TestData.higherOrder, DoneReason.filled))
      assert(matchEvent.takerOrder.asMessage.getSellLimitOrder equalTo OrderUtils.setOrderStatusToDone(TestData.takerSellOrder, DoneReason.filled))
    }
  }
}

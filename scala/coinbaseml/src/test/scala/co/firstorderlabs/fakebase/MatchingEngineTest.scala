package co.firstorderlabs.fakebase

import co.firstorderlabs.fakebase.TestData.RequestsData
import co.firstorderlabs.fakebase.protos.fakebase._
import co.firstorderlabs.fakebase.types.Events.Event
import org.scalatest.funspec.AnyFunSpec

class MatchingEngineTest extends AnyFunSpec {
  Configs.isTest = true
  Exchange.start(RequestsData.simulationStartRequest)

  val buyOrderEvents = List[Event](TestData.OrdersData.higherOrder, TestData.OrdersData.lowerOrder)
  val buyOrderEventsWithCancellation = List[Event](TestData.OrdersData.higherOrder, TestData.OrdersData.lowerOrder, TestData.OrdersData.cancellation)
  val buyOrderEventsAndTakerOrder = List[Event](TestData.OrdersData.higherOrder, TestData.OrdersData.lowerOrder, TestData.OrdersData.takerSellOrder)

  describe("MatchingEngine") {
    it("should do the following when buy orders are added") {
      MatchingEngine.processEvents(buyOrderEvents)

      assert(MatchingEngine.orderBooks(OrderSide.buy).maxOrder.get equalTo TestData.OrdersData.higherOrder)
      assert(MatchingEngine.orderBooks(OrderSide.buy).minOrder.get equalTo TestData.OrdersData.lowerOrder)
      assert(MatchingEngine.orderBooks(OrderSide.buy).maxPrice.get equalTo TestData.OrdersData.higherOrder.price)
      assert(MatchingEngine.orderBooks(OrderSide.buy).minPrice.get equalTo TestData.OrdersData.lowerOrder.price)
      assert(MatchingEngine.checkIsTaker(TestData.OrdersData.takerSellOrder))
    }

    it("should do the following when buy orders are added and one is cancelled") {
      MatchingEngine.processEvents(buyOrderEventsWithCancellation)
      assert(MatchingEngine.orderBooks(OrderSide.buy).maxOrder.get equalTo TestData.OrdersData.lowerOrder)
      assert(MatchingEngine.orderBooks(OrderSide.buy).minOrder.get equalTo TestData.OrdersData.lowerOrder)
    }

    it("should do the following when buy orders are added and a sell order is matched") {
      MatchingEngine.processEvents(buyOrderEventsAndTakerOrder)
      val matchEvent = MatchingEngine.matches(0)
      assert(matchEvent.price equalTo TestData.OrdersData.higherOrder.price)
      assert(matchEvent.size equalTo TestData.OrdersData.higherOrder.size)
      assert(matchEvent.makerOrder.asMessage.getBuyLimitOrder equalTo OrderUtils.setOrderStatusToDone(TestData.OrdersData.higherOrder, DoneReason.filled))
      assert(matchEvent.takerOrder.asMessage.getSellLimitOrder equalTo OrderUtils.setOrderStatusToDone(TestData.OrdersData.takerSellOrder, DoneReason.filled))
    }
  }
}

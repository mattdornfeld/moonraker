package co.firstorderlabs.coinbaseml.fakebase

import co.firstorderlabs.coinbaseml.common.Configs.testMode
import co.firstorderlabs.coinbaseml.common.utils.TestUtils.OrderSideUtils
import co.firstorderlabs.coinbaseml.common.utils.Utils.{When, getResult}
import co.firstorderlabs.coinbaseml.fakebase.TestData.RequestsData
import co.firstorderlabs.coinbaseml.fakebase.utils.OrderUtils
import co.firstorderlabs.common.currency.Configs.ProductPrice
import co.firstorderlabs.common.currency.Configs.ProductPrice.ProductVolume
import co.firstorderlabs.common.protos.events.{DoneReason, OrderSide}
import co.firstorderlabs.common.types.Events.{Event, LimitOrderEvent}
import org.scalatest.funspec.AnyFunSpec

class MatchingEngineTest extends AnyFunSpec {
  testMode = true
  Exchange.start(RequestsData.simulationStartRequest)

  val buyOrderEvents =
    List[Event](TestData.OrdersData.higherOrder, TestData.OrdersData.lowerOrder)
  def buyOrderEventsWithCancellation(implicit
      simulationMetadata: SimulationMetadata
  ) =
    List[Event](
      TestData.OrdersData.higherOrder,
      TestData.OrdersData.lowerOrder,
      TestData.OrdersData.cancellation
    )
  val buyOrderEventsAndTakerOrder = List[Event](
    TestData.OrdersData.higherOrder,
    TestData.OrdersData.lowerOrder,
    TestData.OrdersData.takerSellOrder
  )

  describe("MatchingEngine") {
    it("should do the following when buy orders are added") {
      val simulationId = getResult(
        Exchange.start(RequestsData.simulationStartRequest)
      ).simulationId.get
      val simulationState = SimulationState.getOrFail(simulationId)
      implicit val accountState = simulationState.accountState
      implicit val matchingEngineState = simulationState.matchingEngineState
      implicit val simulationMetadata = simulationState.simulationMetadata
      implicit val buyOrderBookState = matchingEngineState.buyOrderBookState
      MatchingEngine.processEvents(buyOrderEvents)
      println(OrderBook.maxOrder)

      assert(
        OrderBook.maxOrder.get equalTo TestData.OrdersData.higherOrder
      )
      assert(
        OrderBook.iterator.toList.head._2 equalTo TestData.OrdersData.lowerOrder
      )
      assert(
        OrderBook.maxPrice.get equalTo TestData.OrdersData.higherOrder.price
      )
      assert(
        OrderBook.iterator.toList.head._2.price equalTo TestData.OrdersData.lowerOrder.price
      )
      assert(MatchingEngine.checkIsTaker(TestData.OrdersData.takerSellOrder))
    }

    it(
      "should do the following when buy orders are added and one is cancelled"
    ) {
      val simulationId = getResult(
        Exchange.start(RequestsData.simulationStartRequest)
      ).simulationId.get
      val simulationState = SimulationState.getOrFail(simulationId)
      implicit val accountState = simulationState.accountState
      implicit val matchingEngineState = simulationState.matchingEngineState
      implicit val simulationMetadata = simulationState.simulationMetadata
      implicit val buyOrderBookState = matchingEngineState.buyOrderBookState

      MatchingEngine.processEvents(buyOrderEventsWithCancellation)
      assert(
        OrderBook.maxOrder.get equalTo TestData.OrdersData.lowerOrder
      )
      assert(
        OrderBook.iterator.toList.head._2 equalTo TestData.OrdersData.lowerOrder
      )
    }

    it(
      "should do the following when buy orders are added and a sell order is matched"
    ) {
      val simulationId = getResult(
        Exchange.start(RequestsData.simulationStartRequest)
      ).simulationId.get
      val simulationState = SimulationState.getOrFail(simulationId)
      implicit val accountState = simulationState.accountState
      implicit val matchingEngineState = simulationState.matchingEngineState
      implicit val simulationMetadata = simulationState.simulationMetadata
      implicit val buyOrderBookState = matchingEngineState.buyOrderBookState
      implicit val sellOrderBookState = matchingEngineState.sellOrderBookState

      MatchingEngine.processEvents(buyOrderEventsAndTakerOrder)
      val matchEvent = matchingEngineState.matches(0)
      assert(matchEvent.price equalTo TestData.OrdersData.higherOrder.price)
      assert(matchEvent.size equalTo TestData.OrdersData.higherOrder.size)
      assert(
        matchEvent.makerOrder.asMessage.getBuyLimitOrder equalTo OrderUtils
          .setOrderStatusToDone(
            TestData.OrdersData.higherOrder,
            DoneReason.filled
          )
      )
      assert(
        matchEvent.takerOrder.asMessage.getSellLimitOrder equalTo OrderUtils
          .setOrderStatusToDone(
            TestData.OrdersData.takerSellOrder,
            DoneReason.filled
          )
      )
    }

    it(
      "Successively cancel best maker order to ensure next best maker order is the expected one."
    ) {
      List(OrderSide.buy, OrderSide.sell).foreach { orderSide =>
        val simulationId = getResult(
          Exchange.start(RequestsData.simulationStartRequest)
        ).simulationId.get
        val simulationState = SimulationState.getOrFail(simulationId)
        implicit val accountState = simulationState.accountState
        implicit val matchingEngineState = simulationState.matchingEngineState
        implicit val simulationMetadata = simulationState.simulationMetadata

        val receivedOrders = TestUtils
          .generateOrdersForRangeOfPrices(
            new ProductPrice(Right("1.00")),
            new ProductPrice(Right("900.00")),
            new ProductPrice(Right("910.00")),
            orderSide,
            new ProductVolume(Right("1.00")),
            simulationMetadata.currentTimeInterval.endTime
          )
          .asInstanceOf[List[LimitOrderEvent]]

        MatchingEngine.processEvents(receivedOrders.asInstanceOf[List[Event]])

        val openOrders = receivedOrders
          .map(o => OrderUtils.openOrder(o))
          .iterator
          .toList
          .when(orderSide.isbuy)(_.reverse)

        implicit val makerOrderBookState =
          matchingEngineState.getOrderBookState(orderSide)
        openOrders.foreach { expectedBestOrder =>
          assert(
            expectedBestOrder equalTo MatchingEngine
              .getBestMakerOrder(makerOrderBookState)
              .get
          )

          val cancelledOrder = MatchingEngine.cancelOrder(expectedBestOrder)
          assert(cancelledOrder.orderStatus.isdone)
        }
      }
    }
  }
}

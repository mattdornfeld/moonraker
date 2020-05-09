package co.firstorderlabs.fakebase

import java.security.Provider.Service

import co.firstorderlabs.fakebase.protos.fakebase.OrderMessage.SealedValue.SellMarketOrder
import co.firstorderlabs.fakebase.protos.fakebase.{ExchangeServiceGrpc, OrderSide}
import co.firstorderlabs.fakebase.types.Events.{Event, LimitOrderEvent, OrderEvent}
import co.firstorderlabs.fakebase.types.Types.TimeInterval
import com.google.protobuf.empty.Empty

import scala.concurrent.Future

class Exchange extends ExchangeServiceGrpc.ExchangeService {
  private val databaseWorkers = new DatabaseWorkers
  private val matchingEngine = new MatchingEngine
  var currentTimeInterval =
    TimeInterval(Configs.startTime, Configs.endTime)
  private var receivedEvents: List[Event] = List()

  def cancelOrder(order: OrderEvent): OrderEvent = matchingEngine.cancelOrder(order)

  def checkIsTaker(limitOrder: LimitOrderEvent): Boolean = {
    matchingEngine.checkIsTaker(limitOrder)
  }

  def getOrderBook(side: OrderSide): OrderBook = {
    matchingEngine.orderBooks(side)
  }

  override def step(request: Empty): Future[Empty] = {
    currentTimeInterval = currentTimeInterval + Configs.timeDelta
    val queryResult = DatabaseWorkers.getQueryResult(currentTimeInterval)
    receivedEvents = (
      Server.account.getReceivedOrders.toList
      ++ Server.account.getReceivedCancellations.toList
      ++ queryResult.buyLimitOrders
      ++ queryResult.buyMarketOrders
      ++ queryResult.sellLimitOrders
      ++ queryResult.sellMarketOrder
      ++ queryResult.cancellations)
      .sortBy(event => event.time.instant)

    matchingEngine.processEvents(receivedEvents)

    Future.successful(new Empty)
  }
}

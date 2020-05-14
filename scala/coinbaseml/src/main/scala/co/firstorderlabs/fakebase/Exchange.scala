package co.firstorderlabs.fakebase

import java.util.logging.Logger

import co.firstorderlabs.fakebase.protos.fakebase.OrderMessage.SealedValue.SellMarketOrder
import co.firstorderlabs.fakebase.protos.fakebase.{ExchangeServiceGrpc, OrderSide, MatchEvents}
import co.firstorderlabs.fakebase.types.Events.{Event, LimitOrderEvent, OrderEvent}
import co.firstorderlabs.fakebase.types.Types.{Datetime, TimeInterval}
import com.google.protobuf.empty.Empty

import scala.concurrent.Future

class Exchange extends ExchangeServiceGrpc.ExchangeService {
  private val logger = Logger.getLogger(classOf[Exchange].getName)
  private val databaseWorkers = new DatabaseWorkers
  private val matchingEngine = new MatchingEngine
  var currentTimeInterval =
    TimeInterval(
      Datetime(Configs.startTime.instant.minus(Configs.timeDelta)),
      Configs.startTime)
  private var receivedEvents: List[Event] = List()

  databaseWorkers.start

  def cancelOrder(order: OrderEvent): OrderEvent = matchingEngine.cancelOrder(order)

  def checkIsTaker(limitOrder: LimitOrderEvent): Boolean = {
    matchingEngine.checkIsTaker(limitOrder)
  }

  def getOrderBook(side: OrderSide): OrderBook = {
    matchingEngine.orderBooks(side)
  }

  override def getMatches(request: Empty): Future[MatchEvents] = {
    Future.successful(MatchEvents(matchingEngine.matches.toList))
  }

  override def step(request: Empty): Future[Empty] = {
    currentTimeInterval = currentTimeInterval + Configs.timeDelta

    logger.info(s"Stepped to time interval ${currentTimeInterval}")
    logger.info(s"There are ${DatabaseWorkers.getResultMapSize.toString} entries in the results map queue")

    val queryResult = DatabaseWorkers.getQueryResult(currentTimeInterval)
    receivedEvents = (
      FakebaseServer.account.getReceivedOrders.toList
      ++ FakebaseServer.account.getReceivedCancellations.toList
      ++ queryResult.buyLimitOrders
      ++ queryResult.buyMarketOrders
      ++ queryResult.sellLimitOrders
      ++ queryResult.sellMarketOrder
      ++ queryResult.cancellations)
      .sortBy(event => event.time.instant)

    if (receivedEvents.isEmpty)
      logger.warning(s"No events queried for time interval ${currentTimeInterval}")
    else
      logger.info(s"Processing ${receivedEvents.length} events")

    matchingEngine.matches.clear
    matchingEngine.processEvents(receivedEvents)

    Future.successful(new Empty)
  }
}

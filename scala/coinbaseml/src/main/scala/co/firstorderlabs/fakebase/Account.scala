package co.firstorderlabs.fakebase

import java.util.UUID

import co.firstorderlabs.fakebase.currency.Configs.ProductPrice
import co.firstorderlabs.fakebase.currency.Configs.ProductPrice.{ProductVolume, QuoteVolume}
import co.firstorderlabs.fakebase.currency.Volume.{Volume, VolumeCompanion}
import co.firstorderlabs.fakebase.protos.fakebase._
import co.firstorderlabs.fakebase.types.Events._
import co.firstorderlabs.fakebase.types.Types.{Currency, OrderId, TimeInterval}
import io.grpc.Status

import scala.collection.mutable.{HashMap, ListBuffer}
import scala.concurrent.Future

object Account {

  private val wallets = new Wallets

  case class Wallet[A <: Volume[A]](currency: Currency,
                                    initialBalance: A,
                                    walletId: String) {
    var balance = initialBalance
    var holds = initialBalance.companion.zeroVolume

    def setHolds(volume: A) = {
      holds = volume
    }
  }

  class Wallets {
    private val walletsMap = Map(
      ProductVolume.currency -> Wallet(
        ProductVolume.currency,
        new ProductVolume(Right("0.0")),
        UUID.randomUUID().toString
      ),
      QuoteVolume.currency -> Wallet(
        QuoteVolume.currency,
        new QuoteVolume(Right("0.0")),
        UUID.randomUUID().toString
      )
    )

    def removeHolds(order: OrderEvent) = {
      order match {
        case order: BuyOrderEvent => {
          val wallet = getWallet(QuoteVolume)
          val newHolds = wallet.holds - order.holds
          wallet.setHolds(newHolds)
          order.holds = QuoteVolume.zeroVolume
        }
        case order: SellOrderEvent => {
          val wallet = getWallet(ProductVolume)
          val newHolds = wallet.holds - order.holds
          wallet.setHolds(newHolds)
          order.holds = ProductVolume.zeroVolume
        }
      }
    }

    def getAvailableFunds[A <: Volume[A]](
      volume: VolumeCompanion[A]
    ): Volume[A] = {
      val wallet = getWallet(volume)
      wallet.balance - wallet.holds
    }

    def incrementHolds(order: OrderEvent) = {
      order match {
        case order: BuyOrderEvent => {
          val wallet = getWallet(QuoteVolume)
          val orderHolds = Wallets.calcRequiredBuyHold(order)
          val newHolds = wallet.holds + orderHolds
          wallet.setHolds(newHolds)
          order.holds = orderHolds
        }
        case order: SellOrderEvent => {
          val wallet = getWallet(ProductVolume)
          val newHolds = wallet.holds + order.size
          wallet.setHolds(newHolds)
          order.holds = order.size
        }
      }
    }

    def updateBalances(matchEvent: Match) = {
      val productWallet = getWallet(ProductVolume)
      val quoteWallet = getWallet(QuoteVolume)
      matchEvent.getAccountOrder.get match {
        case _:BuyOrderEvent => {
          productWallet.balance = productWallet.balance + matchEvent.size
          quoteWallet.balance = quoteWallet.balance - matchEvent.price * matchEvent.size + matchEvent.fee
        }
        case _:SellOrderEvent => {
          productWallet.balance = productWallet.balance - matchEvent.size
          quoteWallet.balance = quoteWallet.balance + matchEvent.price * matchEvent.size - matchEvent.fee
        }
      }
    }

    def getWallet[A <: Volume[A]](volume: VolumeCompanion[A]): Wallet[A] = {
      walletsMap(volume.currency).asInstanceOf[Wallet[A]]
    }
  }

  class Account extends AccountServiceGrpc.AccountService {
    private val placedCancellations = new HashMap[TimeInterval, ListBuffer[Cancellation]] {
      override def default(key: TimeInterval) = new ListBuffer[Cancellation]
    }
    private val placedOrders: HashMap[OrderId, OrderEvent] = new HashMap
    private val matches = new HashMap[TimeInterval, ListBuffer[Match]] {
      override def default(key: TimeInterval) = new ListBuffer[Match]
    }

    def belongsToAccount(order: OrderEvent): Boolean = {
      placedOrders.contains(order.orderId)
    }

    def closeOrder[A <: OrderEvent](order: A, doneReason: DoneReason): Option[A] = {
      if (placedOrders.contains(order.orderId))
        if (!List(OrderStatus.received, OrderStatus.open).contains(
              order.orderStatus
            )) return None

        wallets.removeHolds(order)
        val updatedOrder = OrderUtils.setOrderStatusToDone(order, doneReason)
        placedOrders.update(updatedOrder.orderId, updatedOrder)
        Some(updatedOrder)
    }

    def openOrder(orderId: OrderId): Option[LimitOrderEvent] = {
      val order = placedOrders.get(orderId)
      order match {
        case Some(order) => {
          order match {
            case order: LimitOrderEvent => {
              val updatedOrder = OrderUtils.openOrder(order)
              placedOrders.update(orderId, updatedOrder)
              Some(updatedOrder)
            }
            case _ => None
          }
        }
        case None => None
      }
    }

    def getReceivedCancellations: Iterable[Cancellation] = {
      for (cancellation <- placedCancellations(Server.exchange.currentTimeInterval))
        yield cancellation
    }

    def getReceivedOrders: Iterable[OrderEvent] = {
      for (order <- placedOrders.values
           if order.orderStatus == OrderStatus.received)
        yield order
    }

    def processMatch(matchEvent: Match) = {
      matches(Server.exchange.currentTimeInterval) += matchEvent
      wallets.updateBalances(matchEvent)
    }

    override def cancelOrder(cancellationRequest: CancellationRequest): Future[Cancellation] = {
      val order = placedOrders.get(cancellationRequest.orderId)


      if (order.isEmpty) {
        return Future.failed(
          Status
            .NOT_FOUND
            .augmentDescription("orderId not found")
            .asRuntimeException
        )
      }

      if (!(order.get.orderStatus.isopen || order.get.orderStatus.isreceived)) {
        return Future.failed(
          Status
            .UNAVAILABLE
            .augmentDescription("can only cancel orders with status open or received")
            .asRuntimeException
        )
      }

      order match {
        case order: LimitOrderEvent => {
          val cancellation = OrderUtils.cancellationFromOrder(order)

          placedCancellations(Server.exchange.currentTimeInterval) += cancellation

          Future.successful(cancellation)
        }
        case _ => Future.failed(
          Status
            .UNAVAILABLE
            .augmentDescription("can only cancel limit orders")
            .asRuntimeException
        )
        }
    }

    override def placeBuyMarketOrder(
      buyMarketOrderRequest: BuyMarketOrderRequest
    ): Future[BuyMarketOrder] = {
      val buyMarketOrder = BuyMarketOrder(
        buyMarketOrderRequest.funds,
        OrderUtils.generateOrderId,
        OrderStatus.received,
        buyMarketOrderRequest.productId,
        OrderSide.buy,
        Server.exchange.currentTimeInterval.endDt,
        RejectReason.notRejected,
        Some(buyMarketOrderRequest)
      )

      val rejectReason = OrderRejecter.isBuyMarketOrderInvalid(buyMarketOrder)
      if (rejectReason.isDefined) {
        buyMarketOrder.update(_.rejectReason := rejectReason.get)
      } else {
        wallets.incrementHolds(buyMarketOrder)
      }
      placedOrders.update(buyMarketOrder.orderId, buyMarketOrder)

      Future.successful(buyMarketOrder)
    }

    override def placeBuyLimitOrder(
      buylimitOrderRequest: BuyLimitOrderRequest
    ): Future[BuyLimitOrder] = {
      val limitOrder = new BuyLimitOrder(
        OrderUtils.generateOrderId,
        OrderStatus.received,
        buylimitOrderRequest.price,
        buylimitOrderRequest.productId,
        OrderSide.buy,
        buylimitOrderRequest.size,
        Server.exchange.currentTimeInterval.endDt,
        RejectReason.notRejected,
        Some(buylimitOrderRequest)
      )

      val rejectReason = OrderRejecter.isLimitOrderInvalid(limitOrder)
      if (rejectReason.isDefined) {
        limitOrder.update(_.rejectReason := rejectReason.get)
      } else {
        wallets.incrementHolds(limitOrder)
      }
      placedOrders.update(limitOrder.orderId, limitOrder)

      Future.successful(limitOrder)
    }

    override def placeSellLimitOrder(
      sellLimitOrderRequest: SellLimitOrderRequest
    ): Future[SellLimitOrder] = {
      val sellLimitOrder = new SellLimitOrder(
        OrderUtils.generateOrderId,
        OrderStatus.received,
        sellLimitOrderRequest.price,
        sellLimitOrderRequest.productId,
        OrderSide.sell,
        sellLimitOrderRequest.size,
        Server.exchange.currentTimeInterval.endDt,
        RejectReason.notRejected,
        Some(sellLimitOrderRequest)
      )

      val rejectReason = OrderRejecter.isLimitOrderInvalid(sellLimitOrder)
      if (rejectReason.isDefined) {
        sellLimitOrder.update(_.rejectReason := rejectReason.get)
      } else {
        wallets.incrementHolds(sellLimitOrder)
      }
      placedOrders.update(sellLimitOrder.orderId, sellLimitOrder)

      Future.successful(sellLimitOrder)
    }

    override def placeSellMarketOrder(
      sellMarketOrderRequest: SellMarketOrderRequest
    ): Future[SellMarketOrder] = {
      val sellMarketOrder = SellMarketOrder(
        OrderUtils.generateOrderId,
        OrderStatus.received,
        sellMarketOrderRequest.productId,
        OrderSide.sell,
        sellMarketOrderRequest.size,
        Server.exchange.currentTimeInterval.endDt,
        RejectReason.notRejected,
        Some(sellMarketOrderRequest)
      )

      val rejectReason = OrderRejecter.isSellMarketOrderInvalid(sellMarketOrder)
      if (rejectReason.isDefined) {
        sellMarketOrder.update(_.rejectReason := rejectReason.get)
      } else {
        wallets.incrementHolds(sellMarketOrder)
      }

      placedOrders.update(sellMarketOrder.orderId, sellMarketOrder)

      Future.successful(sellMarketOrder)
    }
  }

  object Wallets {
    def calcRequiredBuyHold(order: BuyOrderEvent): QuoteVolume = {
      order match {
        case order: BuyLimitOrder  => order.price * order.size
        case order: BuyMarketOrder => order.funds
      }
    }
  }

  object Account {
    def hasSufficientFunds(order: BuyOrderEvent): Boolean = {
      val requiredBuyHold = Wallets.calcRequiredBuyHold(order)
      requiredBuyHold > wallets.getAvailableFunds(QuoteVolume)
    }

    def hasSufficientSize(order: SpecifiesSize): Boolean = {
      val productFunds = wallets.getAvailableFunds(ProductVolume)
      order.size > productFunds
    }
  }

  object OrderRejecter {
    def isBuyMarketOrderInvalid(
      buyMarketOrder: BuyMarketOrder
    ): Option[RejectReason] = {
      List(isFundsInvalid _, hasInsufficientFunds _).view
        .flatMap(f => f(buyMarketOrder))
        .headOption
    }

    private def isFundsInvalid(
      buyMarketOrder: BuyMarketOrder
    ): Option[RejectReason] = {
      buyMarketOrder.funds match {
        case funds if funds > QuoteVolume.maxVolume =>
          Some(RejectReason.fundsTooLarge)
        case funds if funds < QuoteVolume.minVolume =>
          Some(RejectReason.fundsTooSmall)
        case _ => None
      }
    }

    def isLimitOrderInvalid(limitOrder: LimitOrderEvent): Option[RejectReason] = {
      List(
        isPriceInvalid _,
        isSizeInvalid _,
        violatesPostOnly _,
        hasInsufficientFunds _
      ).view
        .flatMap(f => f(limitOrder))
        .headOption
    }

    private def hasInsufficientFunds(order: OrderEvent): Option[RejectReason] = {
      order match {
        case order: BuyOrderEvent => {
          if (Account.hasSufficientFunds(order)) None
          else Some(RejectReason.insufficientFunds)
        }
        case order: SellOrderEvent => {
          if (Account.hasSufficientSize(order)) None
          else Some(RejectReason.insufficientFunds)
        }
      }
    }

    private def isPriceInvalid(limitOrder: LimitOrderEvent): Option[RejectReason] = {
      limitOrder.price match {
        case price if price > ProductPrice.maxPrice =>
          Some(RejectReason.priceTooLarge)
        case price if price < ProductPrice.minPrice =>
          Some(RejectReason.priceTooSmall)
        case _ => None
      }
    }

    private def isSizeInvalid(order: SpecifiesSize): Option[RejectReason] = {
      order.size match {
        case size if size > ProductVolume.maxVolume =>
          Some(RejectReason.sizeTooLarge)
        case size if size < ProductVolume.minVolume =>
          Some(RejectReason.sizeTooSmall)
        case _ => None
      }
    }

    private def violatesPostOnly(
      limitOrder: LimitOrderEvent
    ): Option[RejectReason] = {
      val violatesPostOnly = Server.exchange.checkIsTaker(limitOrder) && limitOrder.request.get.postOnly
      if (violatesPostOnly) Some(RejectReason.postOnly) else None
    }

    def isSellMarketOrderInvalid(
      sellMarketOrder: SellMarketOrder
    ): Option[RejectReason] = {
      List(isSizeInvalid _, hasInsufficientFunds _).view
        .flatMap(f => f(sellMarketOrder))
        .headOption
    }
  }
}

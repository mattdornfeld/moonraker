package co.firstorderlabs.bqwriter

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

import co.firstorderlabs.common.currency.Configs.ProductPrice
import co.firstorderlabs.common.protos.events.{DoneReason, OrderSide}
import co.firstorderlabs.common.protos.fakebase.OrderType
import info.bitrich.xchangestream.coinbasepro.dto.CoinbaseProWebSocketTransaction

object TestData {
  private val cancellationTransactionArgs = List(
    EventTypes.DONE,
    UUID.randomUUID.toString, // orderId
    null,
    null,
    new BigDecimal("1.00"), // remainingSize
    new BigDecimal("100.00"), // price
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    OrderSide.buy.name,
    null,
    null,
    null,
    null,
    ProductPrice.productId.toString,
    UUID.randomUUID.hashCode.toLong, // sequenceId
    Instant.now.toString, // time
    DoneReason.canceled.name,
    null,
    null,
    null,
    null,
    null,
    null,
    null
  )

  private val matchTransactionArgs = List(
    EventTypes.MATCH,
    null,
    null,
    new BigDecimal("1.00"), // size
    null,
    new BigDecimal("100.00"), // price
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    OrderSide.buy.name,
    null,
    null,
    null,
    null,
    ProductPrice.productId.toString,
    UUID.randomUUID().hashCode.toLong, // sequenceId
    Instant.now.toString, // time
    null,
    100L, // tradeId
    UUID.randomUUID.toString, // makerOrderId
    UUID.randomUUID.toString, // takerOrderId
    null,
    null,
    null,
    null
  )

  private val buyLimitOrderTransactionArgs = List(
    EventTypes.RECEIVED,
    UUID.randomUUID.toString, // orderId
    OrderType.limit.name,
    new BigDecimal("1.00"), // size
    null,
    new BigDecimal("100.00"), // price
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    OrderSide.buy.name,
    null,
    null,
    null,
    null,
    ProductPrice.productId.toString,
    UUID.randomUUID().hashCode.toLong, // sequenceId
    Instant.now.toString, // time
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    null
  )

  private val sellLimitOrderTransactionArgs = List(
    EventTypes.RECEIVED,
    UUID.randomUUID.toString, // orderId
    OrderType.limit.name,
    new BigDecimal("1.00"), // size
    null,
    new BigDecimal("100.00"), // price
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    OrderSide.sell.name,
    null,
    null,
    null,
    null,
    ProductPrice.productId.toString,
    UUID.randomUUID().hashCode.toLong, // sequenceId
    Instant.now.toString, // time
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    null
  )

  private val buyMarketOrderTransactionArgs = List(
    EventTypes.RECEIVED,
    UUID.randomUUID.toString, // orderId
    OrderType.market.name,
    new BigDecimal("1.00"), // size
    null,
    null, // price
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    OrderSide.buy.name,
    null,
    null,
    null,
    null,
    ProductPrice.productId.toString,
    UUID.randomUUID().hashCode.toLong, // sequenceId
    Instant.now.toString, // time
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    null
  )

  private val sellMarketOrderTransactionArgs = List(
    EventTypes.RECEIVED,
    UUID.randomUUID.toString, // orderId
    OrderType.market.name,
    new BigDecimal("1.00"), // size
    null,
    null, // price
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    OrderSide.buy.name,
    null,
    null,
    null,
    null,
    ProductPrice.productId.toString,
    UUID.randomUUID().hashCode.toLong, // sequenceId
    Instant.now.toString, // time
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    null
  )

  def buildTransaction(args: List[Any]): CoinbaseProWebSocketTransaction = {
    new CoinbaseProWebSocketTransaction(
      args(0).asInstanceOf[String],
      args(1).asInstanceOf[String],
      args(2).asInstanceOf[String],
      args(3).asInstanceOf[BigDecimal],
      args(4).asInstanceOf[BigDecimal],
      args(5).asInstanceOf[BigDecimal],
      args(6).asInstanceOf[BigDecimal],
      args(7).asInstanceOf[BigDecimal],
      args(8).asInstanceOf[BigDecimal],
      args(9).asInstanceOf[BigDecimal],
      args(10).asInstanceOf[BigDecimal],
      args(11).asInstanceOf[BigDecimal],
      args(12).asInstanceOf[BigDecimal],
      args(13).asInstanceOf[String],
      args(14).asInstanceOf[Array[Array[String]]],
      args(15).asInstanceOf[Array[Array[String]]],
      args(16).asInstanceOf[Array[Array[String]]],
      args(17).asInstanceOf[String],
      args(18).asInstanceOf[String],
      UUID.randomUUID().hashCode.toLong,
      args(20).asInstanceOf[String],
      args(21).asInstanceOf[String],
      args(22).asInstanceOf[Long],
      args(23).asInstanceOf[String],
      args(24).asInstanceOf[String],
      args(25).asInstanceOf[String],
      args(26).asInstanceOf[String],
      args(27).asInstanceOf[String],
      args(28).asInstanceOf[String],
    )
  }

  def buildInvalidTransactions(transactionArgs: List[Any]): List[CoinbaseProWebSocketTransaction] = {
    val nonNullIndices = transactionArgs.zipWithIndex.filter(item => item._1 != null && item._2 != 19).map(_._2)
    nonNullIndices.map(i => buildTransaction(transactionArgs.zipWithIndex.map{item => if (i == item._2) null else item._1}))
  }

  def buyLimitOrderTransaction: CoinbaseProWebSocketTransaction = buildTransaction(buyLimitOrderTransactionArgs)

  def invalidBuyLimitOrderTransactions: List[CoinbaseProWebSocketTransaction] = buildInvalidTransactions(buyLimitOrderTransactionArgs)

  def sellLimitOrderTransaction: CoinbaseProWebSocketTransaction = buildTransaction(sellLimitOrderTransactionArgs)

  def invalidSellLimitOrderTransactions: List[CoinbaseProWebSocketTransaction] = buildInvalidTransactions(sellLimitOrderTransactionArgs)

  def buyMarketOrderTransaction: CoinbaseProWebSocketTransaction = buildTransaction(buyMarketOrderTransactionArgs)

  def invalidBuyMarketOrderTransactions: List[CoinbaseProWebSocketTransaction] = buildInvalidTransactions(buyMarketOrderTransactionArgs)

  def sellMarketOrderTransaction: CoinbaseProWebSocketTransaction = buildTransaction(sellMarketOrderTransactionArgs)

  def invalidSellMarketOrderTransactions: List[CoinbaseProWebSocketTransaction] = buildInvalidTransactions(sellMarketOrderTransactionArgs)

  def cancellationTransaction: CoinbaseProWebSocketTransaction = buildTransaction(cancellationTransactionArgs)

  def invalidCancellationTransactions: List[CoinbaseProWebSocketTransaction] = buildInvalidTransactions(cancellationTransactionArgs)

  def matchTransaction: CoinbaseProWebSocketTransaction = buildTransaction(matchTransactionArgs)

  def invalidMatchTransactions: List[CoinbaseProWebSocketTransaction] = buildInvalidTransactions(matchTransactionArgs)
}

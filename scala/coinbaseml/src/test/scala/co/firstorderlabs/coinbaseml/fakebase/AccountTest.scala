package co.firstorderlabs.coinbaseml.fakebase

import java.time.Duration

import co.firstorderlabs.coinbaseml.common.utils.Utils.getResult
import co.firstorderlabs.coinbaseml.fakebase.TestData.OrdersData
import co.firstorderlabs.coinbaseml.fakebase.TestData.RequestsData._
import co.firstorderlabs.coinbaseml.fakebase.protos.fakebase.{Wallets => _, _}
import co.firstorderlabs.coinbaseml.fakebase.types.Events.{BuyOrderRequest, SellOrderRequest}
import co.firstorderlabs.coinbaseml.fakebase.utils.OrderUtils
import co.firstorderlabs.common.currency.Configs.ProductPrice
import co.firstorderlabs.common.currency.Configs.ProductPrice.QuoteVolume
import co.firstorderlabs.common.currency.Price.BtcUsdPrice.ProductVolume
import co.firstorderlabs.common.protos.events._
import co.firstorderlabs.common.types.Events._
import org.scalatest.funspec.AnyFunSpec

class AccountTest extends AnyFunSpec {
  Configs.testMode = true
  describe("Account") {
    it("Wallets should be updated accordingly when an order is placed") {
      List(
        buyLimitOrderRequest,
        buyMarketOrderRequest,
        sellLimitOrderRequest,
        sellMarketOrderRequest
      ).foreach { orderRequest =>
        Exchange.start(simulationStartRequest)

        val orderFuture = orderRequest match {
          case orderRequest: BuyLimitOrderRequest =>
            Account.placeBuyLimitOrder(orderRequest)
          case orderRequest: BuyMarketOrderRequest =>
            Account.placeBuyMarketOrder(orderRequest)
          case orderRequest: SellLimitOrderRequest =>
            Account.placeSellLimitOrder(orderRequest)
          case orderRequest: SellMarketOrderRequest =>
            Account.placeSellMarketOrder(orderRequest)
        }

        val order = getResult(orderFuture)

        val productWallet = Wallets.getWallet(ProductVolume)
        val quoteWallet = Wallets.getWallet(QuoteVolume)

        order match {
          case order: BuyOrderEvent => {
            assert(order.holds equalTo Wallets.calcRequiredBuyHold(order))
            assert(productWallet.holds equalTo ProductVolume.zeroVolume)
            assert(quoteWallet.holds equalTo order.holds)
          }
          case order: SellOrderEvent => {
            assert(order.holds equalTo order.size)
            assert(productWallet.holds equalTo order.holds)
            assert(quoteWallet.holds equalTo QuoteVolume.zeroVolume)
          }
        }
      }
    }
    it(
      "An order in a received status should be put in a cancelled state when cancelled."
    ) {
      List(
        buyLimitOrderRequest,
        buyMarketOrderRequest,
        sellLimitOrderRequest,
        sellMarketOrderRequest
      ).foreach { orderRequest =>
        Exchange.start(simulationStartRequest)

        val orderFuture = orderRequest match {
          case orderRequest: BuyLimitOrderRequest =>
            Account.placeBuyLimitOrder(orderRequest)
          case orderRequest: BuyMarketOrderRequest =>
            Account.placeBuyMarketOrder(orderRequest)
          case orderRequest: SellLimitOrderRequest =>
            Account.placeSellLimitOrder(orderRequest)
          case orderRequest: SellMarketOrderRequest =>
            Account.placeSellMarketOrder(orderRequest)
        }

        val order = getResult(orderFuture)

        val productWallet = Wallets.getWallet(ProductVolume)
        val quoteWallet = Wallets.getWallet(QuoteVolume)

        order match {
          case order: BuyOrderEvent => assert(quoteWallet.holds equalTo order.holds)
          case order: SellOrderEvent => assert(productWallet.holds equalTo order.holds)
        }

        Account.cancelOrder(new CancellationRequest(order.orderId))

        Exchange.step(Constants.emptyStepRequest)

        assert(Account.placedOrders(order.orderId).orderStatus.isdone)
        assert(Account.placedOrders(order.orderId).doneReason.iscanceled)
        assert(
          Exchange
            .getOrderBook(order.side)
            .getOrderByOrderId(order.orderId)
            .isEmpty
        )

        assert(productWallet.holds.isZero)
        assert(quoteWallet.holds.isZero)
      }
    }
    it(
      "A maker limit order should be available on the appropriate order book when placed and removed when cancelled"
    ) {
      List(buyLimitOrderRequest, sellLimitOrderRequest).foreach {
        orderRequest =>
          Exchange.start(simulationStartRequest)

          val orderFuture = orderRequest match {
            case orderRequest: BuyLimitOrderRequest =>
              Account.placeBuyLimitOrder(orderRequest)
            case orderRequest: SellLimitOrderRequest =>
              Account.placeSellLimitOrder(orderRequest)
          }

          val order = getResult(orderFuture)

          Exchange.step(Constants.emptyStepRequest)

          assert(
            Exchange
              .getOrderBook(order.side)
              .getOrderByOrderId(order.orderId)
              .isDefined
          )

          val cancellationRequest = new CancellationRequest(order.orderId)
          val cancellation = getResult(Account.cancelOrder(cancellationRequest))

          assert(cancellation.orderId == order.orderId)

          Exchange.step(Constants.emptyStepRequest)

          assert(
            Exchange
              .getOrderBook(order.side)
              .getOrderByOrderId(order.orderId)
              .isEmpty
          )

          val productWallet = Wallets.getWallet(ProductVolume)
          val quoteWallet = Wallets.getWallet(QuoteVolume)

          assert(
            productWallet.balance equalTo simulationStartRequest.initialProductFunds
          )
          assert(
            quoteWallet.balance equalTo simulationStartRequest.initialQuoteFunds
          )
          assert(productWallet.holds.isZero)
          assert(quoteWallet.holds.isZero)
      }
    }

    it(
      "When a maker limit order is placed it shouldn't match with any orders on the opposing order book and it should be " +
        "open on the order book for its side."
    ) {
      List(buyLimitOrderRequest, sellLimitOrderRequest).foreach {
        orderRequest =>
          Exchange.start(simulationStartRequest)

          val orderFuture = orderRequest match {
            case orderRequest: BuyLimitOrderRequest =>
              Account.placeBuyLimitOrder(orderRequest)
            case orderRequest: SellLimitOrderRequest =>
              Account.placeSellLimitOrder(orderRequest)
          }

          val order = getResult(orderFuture)

          val stepRequest = new StepRequest(insertOrders = orderRequest match {
            case _: BuyLimitOrderRequest =>
              OrdersData.insertSellOrders(
                buyLimitOrderRequest.price + new ProductPrice(Right("100.00"))
              )
            case _: SellLimitOrderRequest =>
              OrdersData.insertBuyOrders(sellLimitOrderRequest.price)
          })

          Exchange.step(stepRequest)

          val orderBook =
            Exchange.getOrderBook(OrderUtils.getOppositeSide(order.side))

          order match {
            case order: BuyLimitOrder =>
              assert(order.price < orderBook.minPrice.get)
            case order: SellLimitOrder =>
              assert(order.price > orderBook.maxPrice.get)
          }

          val openOrder = Account.placedOrders.get(order.orderId).get match {
            case openOrder: LimitOrderEvent => openOrder
          }

          assert(
            Exchange
              .getOrderBook(openOrder.side)
              .getOrderByOrderId(openOrder.orderId)
              .isDefined
          )
          assert(openOrder.orderStatus.isopen)

          val productWallet = Wallets.getWallet(ProductVolume)
          val quoteWallet = Wallets.getWallet(QuoteVolume)

          assert(
            productWallet.balance equalTo simulationStartRequest.initialProductFunds
          )
          assert(
            quoteWallet.balance equalTo simulationStartRequest.initialQuoteFunds
          )
          order match {
            case order: BuyOrderEvent =>
              assert(quoteWallet.holds equalTo order.holds)
            case order: SellOrderEvent =>
              assert(productWallet.holds equalTo order.holds)
          }
      }
    }

    it(
      "A taker limit order should be matched with orders from the opposing order book until its remainingSize is 0"
    ) {
      List(buyLimitOrderRequest, sellLimitOrderRequest).foreach {
        orderRequest =>
          Exchange.start(simulationStartRequest)

          val stepRequest = new StepRequest(insertOrders = orderRequest match {
            case _: BuyLimitOrderRequest =>
              OrdersData.insertSellOrders(
                buyLimitOrderRequest.price - new ProductPrice(Right("100.00")),
                new ProductVolume(Right("0.5"))
              )
            case _: SellLimitOrderRequest =>
              OrdersData.insertBuyOrders(
                sellLimitOrderRequest.price + new ProductPrice(Right("200.00")),
                new ProductVolume(Right("0.5"))
              )
          })

          Exchange.step(stepRequest)

          val expectedMatchPrices =
            TestUtils.getOrderBookPrices(2, orderRequest match {
              case _: BuyOrderRequest  => OrderSide.sell
              case _: SellOrderRequest => OrderSide.buy
            })

          val orderFuture = orderRequest match {
            case orderRequest: BuyLimitOrderRequest =>
              Account.placeBuyLimitOrder(orderRequest)
            case orderRequest: SellLimitOrderRequest =>
              Account.placeSellLimitOrder(orderRequest)
          }

          val order = getResult(orderFuture)

          val productWallet = Wallets.getWallet(ProductVolume)
          val quoteWallet = Wallets.getWallet(QuoteVolume)

          Exchange.step(Constants.emptyStepRequest)

          val doneOrder = Account.placedOrders.get(order.orderId).get match {
            case doneOrder: LimitOrderEvent => doneOrder
          }

          val matchEvents = Account.matches(doneOrder.orderId)

          // Match prices are better than limit prices and best prices from order book were obtained
          doneOrder match {
            case doneOrder: BuyLimitOrder => {
              matchEvents.foreach(m => assert(m.price <= doneOrder.price))
              matchEvents.foreach(
                m =>
                  assert(
                    m.price <= Exchange
                      .getOrderBook(OrderSide.sell)
                      .minPrice
                      .get
                )
              )
            }
            case doneOrder: SellLimitOrder => {
              matchEvents.foreach(m => assert(m.price >= doneOrder.price))
              matchEvents.foreach(
                m =>
                  assert(
                    m.price >= Exchange.getOrderBook(OrderSide.buy).maxPrice.get
                )
              )
            }
          }

          // Filled volume equals to order size
          val matchedSize = matchEvents.map(m => m.size).reduce(_ + _)
          assert(doneOrder.size equalTo matchedSize)

          // Matched prices equal expected matched prices
          expectedMatchPrices
            .zipAll(
              matchEvents.map(m => m.price),
              ProductPrice.zeroPrice,
              ProductPrice.zeroPrice
            )
            .foreach(item => assert(item._1 equalTo item._2))

          assert(doneOrder.orderStatus.isdone)
          assert(doneOrder.remainingSize.isZero)
          matchEvents.foreach(m => assert(m.liquidity.istaker))
          matchEvents
            .map(m => m.getAccountOrder.get)
            .foreach(o => assert(o.orderId == doneOrder.orderId))

          val matchedVolume = matchEvents.map(m => m.quoteVolume).reduce(_ + _)
          val fees = matchEvents.map(m => m.fee).reduce(_ + _)
          doneOrder match {
            case _: BuyOrderEvent => {
              assert(
                productWallet.balance equalTo simulationStartRequest.initialProductFunds + matchedSize
              )
              assert(
                quoteWallet.balance equalTo simulationStartRequest.initialQuoteFunds - matchedVolume - fees
              )
              assert(productWallet.holds equalTo ProductVolume.zeroVolume)
              assert(quoteWallet.holds equalTo QuoteVolume.zeroVolume)
            }
            case _: SellOrderEvent => {
              assert(
                productWallet.balance equalTo simulationStartRequest.initialProductFunds - matchedSize
              )
              assert(
                quoteWallet.balance equalTo simulationStartRequest.initialQuoteFunds + matchedVolume - fees
              )
              assert(productWallet.holds equalTo ProductVolume.zeroVolume)
              assert(quoteWallet.holds equalTo QuoteVolume.zeroVolume)
            }
          }
      }
    }

    it(
      "A taker limit order should match with orders from the opposing order book until its limit price is reached. " +
        "The remainder should be be put on the order book."
    ) {
      List(buyLimitOrderRequest, sellLimitOrderRequest).foreach {
        orderRequest =>
          Exchange.start(simulationStartRequest)

          val initialOrderBookVolume = new ProductVolume(Right("0.001"))

          val stepRequest = new StepRequest(insertOrders = orderRequest match {
            case orderRequest: BuyLimitOrderRequest =>
              Seq(
                new SellLimitOrder(
                  OrderUtils.generateOrderId,
                  OrderStatus.received,
                  orderRequest.price - OrdersData.priceDelta,
                  ProductPrice.productId,
                  OrderSide.sell,
                  initialOrderBookVolume,
                  Exchange.getSimulationMetadata.currentTimeInterval.startTime
                )
              )
            case orderRequest: SellLimitOrderRequest =>
              Seq(
                new BuyLimitOrder(
                  OrderUtils.generateOrderId,
                  OrderStatus.received,
                  orderRequest.price + OrdersData.priceDelta,
                  ProductPrice.productId,
                  OrderSide.buy,
                  initialOrderBookVolume,
                  Exchange.getSimulationMetadata.currentTimeInterval.startTime
                )
              )
          })

          Exchange.step(stepRequest)

          val expectedMatchPrices =
            TestUtils.getOrderBookPrices(1, orderRequest match {
              case _: BuyOrderRequest  => OrderSide.sell
              case _: SellOrderRequest => OrderSide.buy
            })

          val orderFuture = orderRequest match {
            case orderRequest: BuyLimitOrderRequest =>
              Account.placeBuyLimitOrder(orderRequest)
            case orderRequest: SellLimitOrderRequest =>
              Account.placeSellLimitOrder(orderRequest)
          }

          val order = getResult(orderFuture)

          Exchange.step(Constants.emptyStepRequest)

          val openOrder = Account.placedOrders.get(order.orderId).get match {
            case openOrder: LimitOrderEvent => openOrder
          }

          val matchEvents = Account.matches(openOrder.orderId)
          val matchedSize = matchEvents.map(m => m.size).reduce(_ + _)

          assert(initialOrderBookVolume equalTo matchedSize)

          expectedMatchPrices
            .zipAll(
              matchEvents.map(m => m.price),
              ProductPrice.zeroPrice,
              ProductPrice.zeroPrice
            )
            .foreach(item => assert(item._1 equalTo item._2))

          assert(openOrder.orderStatus.isopen)
          assert(
            Exchange
              .getOrderBook(order.side)
              .getOrderByOrderId(order.orderId)
              .isDefined
          )
          assert(
            openOrder.remainingSize equalTo openOrder.size - initialOrderBookVolume
          )

          val productWallet = Wallets.getWallet(ProductVolume)
          val quoteWallet = Wallets.getWallet(QuoteVolume)

          val matchedVolume = matchEvents.map(m => m.quoteVolume).reduce(_ + _)
          val fees = matchEvents.map(m => m.fee).reduce(_ + _)
          openOrder match {
            case openOrder: BuyOrderEvent => {
              val balanceDelta = matchedVolume + fees
              assert(
                productWallet.balance equalTo simulationStartRequest.initialProductFunds + matchedSize
              )
              assert(
                quoteWallet.balance equalTo simulationStartRequest.initialQuoteFunds - balanceDelta
              )
              assert(productWallet.holds equalTo ProductVolume.zeroVolume)
              assert(
                quoteWallet.holds equalTo Wallets
                  .calcRequiredBuyHold(openOrder) - balanceDelta
              )
              assert(quoteWallet.holds equalTo openOrder.holds)
            }
            case openOrder: SellOrderEvent => {
              assert(
                productWallet.balance equalTo simulationStartRequest.initialProductFunds - matchedSize
              )
              assert(
                quoteWallet.balance equalTo simulationStartRequest.initialQuoteFunds + matchedVolume - fees
              )
              assert(productWallet.holds equalTo openOrder.size - matchedSize)
              assert(quoteWallet.holds equalTo QuoteVolume.zeroVolume)
              assert(productWallet.holds equalTo openOrder.holds)
            }
          }
      }
    }

    it(
      "A taker limit order should match with orders on the opposing order book until the slippage limit is reached. " +
        "It should then be cancelled."
    ) {
      val buyLimitOrderRequest = new BuyLimitOrderRequest(
        new ProductPrice(Right("200.00")),
        ProductPrice.productId,
        new ProductVolume(Right("10.000000")),
        false
      )

      val sellLimitOrderRequest = new SellLimitOrderRequest(
        new ProductPrice(Right("50.00")),
        ProductPrice.productId,
        new ProductVolume(Right("10.000000")),
        false
      )

      List(
        buyLimitOrderRequest,
        buyMarketOrderRequest,
        sellLimitOrderRequest,
        sellMarketOrderRequest
      ).foreach { orderRequest =>
        Exchange.start(simulationStartRequest)

        val stepRequest = new StepRequest(insertOrders = orderRequest match {
          case _: BuyOrderRequest =>
            OrdersData.insertSellOrders(
              new ProductPrice(Right("90.00")),
              new ProductVolume(Right("0.05"))
            )
          case _: SellOrderRequest =>
            OrdersData.insertBuyOrders(
              new ProductPrice(Right("110.00")),
              new ProductVolume(Right("0.05"))
            )
        })

        Exchange.step(stepRequest)

        val orderFuture = orderRequest match {
          case orderRequest: BuyLimitOrderRequest =>
            Account.placeBuyLimitOrder(orderRequest)
          case orderRequest: BuyMarketOrderRequest =>
            Account.placeBuyMarketOrder(orderRequest)
          case orderRequest: SellLimitOrderRequest =>
            Account.placeSellLimitOrder(orderRequest)
          case orderRequest: SellMarketOrderRequest =>
            Account.placeSellMarketOrder(orderRequest)
        }

        val order = getResult(orderFuture)

        Exchange.step(Constants.emptyStepRequest)

        val cancelledOrder = Account.placedOrders.get(order.orderId).get

        val matchEvents = Account.matches(cancelledOrder.orderId)
        val firstMatchPrice =
          matchEvents.map(m => m.price)(0)
        val lastMatchPrice =
          matchEvents.map(m => m.price).last

        assert(cancelledOrder.orderStatus.isdone)
        assert(cancelledOrder.doneReason.iscanceled)
        assert(
          SlippageProtection
            .getPriceSlippagePoints(firstMatchPrice, lastMatchPrice)
            .compareTo(SlippageProtection.maxPriceSlippagePoints) <= 0
        )

        val productWallet = Wallets.getWallet(ProductVolume)
        val quoteWallet = Wallets.getWallet(QuoteVolume)

        val matchedSize = matchEvents.map(m => m.size).reduce(_ + _)
        val matchedVolume = matchEvents.map(m => m.quoteVolume).reduce(_ + _)
        val fees = matchEvents.map(m => m.fee).reduce(_ + _)
        cancelledOrder match {
          case _: BuyOrderEvent => {
            assert(
              productWallet.balance equalTo simulationStartRequest.initialProductFunds + matchedSize
            )
            assert(productWallet.holds equalTo ProductVolume.zeroVolume)
            assert(
              quoteWallet.balance equalTo simulationStartRequest.initialQuoteFunds - matchedVolume - fees
            )
            assert(quoteWallet.holds equalTo QuoteVolume.zeroVolume)
          }
          case _: SellOrderEvent => {
            assert(
              productWallet.balance equalTo simulationStartRequest.initialProductFunds - matchedSize
            )
            assert(productWallet.holds equalTo ProductVolume.zeroVolume)
            assert(
              quoteWallet.balance equalTo simulationStartRequest.initialQuoteFunds + matchedVolume - fees
            )
            assert(quoteWallet.holds equalTo QuoteVolume.zeroVolume)
          }
        }
      }
    }

    it(
      "A market order should match with maker orders on the opposing order book"
    ) {
      List(buyMarketOrderRequest, sellMarketOrderRequest).foreach {
        orderRequest =>
          Exchange.start(simulationStartRequest)

          val stepRequest = new StepRequest(insertOrders = orderRequest match {
            case _: BuyMarketOrderRequest =>
              OrdersData.insertSellOrders(
                new ProductPrice(Right("1000.00")),
                new ProductVolume(Right("0.5"))
              )
            case _: SellMarketOrderRequest =>
              OrdersData.insertBuyOrders(
                new ProductPrice(Right("1000.00")),
                new ProductVolume(Right("0.5"))
              )
          })

          Exchange.step(stepRequest)

          val expectedMatchPrices =
            TestUtils.getOrderBookPrices(2, orderRequest match {
              case _: BuyMarketOrderRequest  => OrderSide.sell
              case _: SellMarketOrderRequest => OrderSide.buy
            })

          val orderFuture = orderRequest match {
            case orderRequest: BuyMarketOrderRequest =>
              Account.placeBuyMarketOrder(orderRequest)
            case orderRequest: SellMarketOrderRequest =>
              Account.placeSellMarketOrder(orderRequest)
          }

          val order = getResult(orderFuture)

          Exchange.step(Constants.emptyStepRequest)

          val doneOrder = Account.placedOrders.get(order.orderId).get
          val matchEvents = Account.matches(order.orderId)
          val matchPrices = matchEvents.map(m => m.price)

          assert(doneOrder.orderStatus.isdone)
          assert(doneOrder.doneReason.isfilled)
          assert(
            Exchange
              .getOrderBook(doneOrder.side)
              .getOrderByOrderId(doneOrder.orderId)
              .isEmpty
          )
          expectedMatchPrices
            .zipAll(matchPrices, ProductPrice.zeroPrice, ProductPrice.zeroPrice)
            .foreach(item => assert(item._1 equalTo item._2))


          val matchedSize = matchEvents.map(m => m.size).reduce(_ + _)
          val matchedVolume = matchEvents.map(m => m.quoteVolume).reduce(_ + _)
          val fees = matchEvents.map(m => m.fee).reduce(_ + _)
          val productWallet = Wallets.getWallet(ProductVolume)
          val quoteWallet = Wallets.getWallet(QuoteVolume)

          doneOrder match {
            case _: BuyOrderEvent => {
              assert(
                productWallet.balance equalTo simulationStartRequest.initialProductFunds + matchedSize
              )
              assert(productWallet.holds equalTo ProductVolume.zeroVolume)
              assert(
                quoteWallet.balance equalTo simulationStartRequest.initialQuoteFunds - matchedVolume - fees
              )
              assert(quoteWallet.holds equalTo QuoteVolume.zeroVolume)
            }
            case _: SellOrderEvent => {
              assert(
                productWallet.balance equalTo simulationStartRequest.initialProductFunds - matchedSize
              )
              assert(productWallet.holds equalTo ProductVolume.zeroVolume)
              assert(
                quoteWallet.balance equalTo simulationStartRequest.initialQuoteFunds + matchedVolume - fees
              )
              assert(quoteWallet.holds equalTo QuoteVolume.zeroVolume)
            }
          }
      }
    }

    it(
      "A market order should be cancelled if there are no orders on the opposing order book"
    ) {
      List(buyMarketOrderRequest, sellMarketOrderRequest).foreach {
        orderRequest =>
          Exchange.start(simulationStartRequest)

          val orderFuture = orderRequest match {
            case orderRequest: BuyMarketOrderRequest =>
              Account.placeBuyMarketOrder(orderRequest)
            case orderRequest: SellMarketOrderRequest =>
              Account.placeSellMarketOrder(orderRequest)
          }

          val order = getResult(orderFuture)

          Exchange.step(Constants.emptyStepRequest)

          val cancelledOrder = Account.placedOrders.get(order.orderId).get

          assert(cancelledOrder.orderStatus.isdone)
          assert(cancelledOrder.doneReason.iscanceled)
          assert(
            Exchange
              .getOrderBook(cancelledOrder.side)
              .getOrderByOrderId(cancelledOrder.orderId)
              .isEmpty
          )

          val productWallet = Wallets.getWallet(ProductVolume)
          val quoteWallet = Wallets.getWallet(QuoteVolume)

          assert(productWallet.balance equalTo simulationStartRequest.initialProductFunds)
          assert(productWallet.holds equalTo ProductVolume.zeroVolume)
          assert(quoteWallet.balance equalTo simulationStartRequest.initialQuoteFunds)
          assert(quoteWallet.holds equalTo QuoteVolume.zeroVolume)
      }
    }

    it(
      "A taker order that matches with an order from the same account should be cancelled."
    ) {
      val buyLimitOrderRequest = new BuyLimitOrderRequest(
        new ProductPrice(Right("200.00")),
        ProductPrice.productId,
        new ProductVolume(Right("10.000000")),
        false,
        Some(Duration.ofDays(10)),
      )

      val sellLimitOrderRequest = new SellLimitOrderRequest(
        new ProductPrice(Right("50.00")),
        ProductPrice.productId,
        new ProductVolume(Right("10.000000")),
        false,
        Some(Duration.ofDays(10)),
      )

      List(
        buyLimitOrderRequest,
        buyMarketOrderRequest,
        sellLimitOrderRequest,
        sellMarketOrderRequest
      ).foreach { orderRequest =>
        Exchange.start(simulationStartRequest)

        val makerOrderFuture = orderRequest match {
          case _: BuyOrderRequest =>
            Account.placeSellLimitOrder(sellLimitOrderRequest)
          case _: SellOrderRequest =>
            Account.placeBuyLimitOrder(buyLimitOrderRequest)
        }

        val makerOrder = getResult(makerOrderFuture)

        Exchange.step(Constants.emptyStepRequest)

        val takerOrderFuture = orderRequest match {
          case orderRequest: BuyLimitOrderRequest =>
            Account.placeBuyLimitOrder(orderRequest)
          case orderRequest: BuyMarketOrderRequest =>
            Account.placeBuyMarketOrder(orderRequest)
          case orderRequest: SellLimitOrderRequest =>
            Account.placeSellLimitOrder(orderRequest)
          case orderRequest: SellMarketOrderRequest =>
            Account.placeSellMarketOrder(orderRequest)
        }

        val takerOrder = getResult(takerOrderFuture)

        Exchange.step(Constants.emptyStepRequest)

        assert(Account.placedOrders(takerOrder.orderId).orderStatus.isdone)
        assert(Account.placedOrders(takerOrder.orderId).doneReason.iscanceled)
        assert(Account.matches(takerOrder.orderId).isEmpty)
        assert(
          Exchange
            .getOrderBook(takerOrder.side)
            .getOrderByOrderId(takerOrder.orderId)
            .isEmpty
        )
        assert(
          Exchange
            .getOrderBook(makerOrder.side)
            .getOrderByOrderId(makerOrder.orderId)
            .get
            .remainingSize equalTo makerOrder.size
        )

        val productWallet = Wallets.getWallet(ProductVolume)
        val quoteWallet = Wallets.getWallet(QuoteVolume)

        assert(productWallet.balance equalTo simulationStartRequest.initialProductFunds)
        assert(quoteWallet.balance equalTo simulationStartRequest.initialQuoteFunds)
        makerOrder match {
          case makerOrder: BuyOrderEvent => {
            assert(productWallet.holds equalTo ProductVolume.zeroVolume)
            assert(quoteWallet.holds equalTo makerOrder.holds)
          }
          case makerOrder: SellOrderEvent =>
            assert(productWallet.holds equalTo makerOrder.holds)
            assert(quoteWallet.holds equalTo QuoteVolume.zeroVolume)
        }
      }
    }
  }
}

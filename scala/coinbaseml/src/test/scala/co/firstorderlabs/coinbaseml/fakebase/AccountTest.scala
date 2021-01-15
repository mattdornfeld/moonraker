package co.firstorderlabs.coinbaseml.fakebase

import java.time.Duration

import co.firstorderlabs.coinbaseml.common.utils.TestUtils.buildStepRequest
import co.firstorderlabs.coinbaseml.common.utils.Utils.getResult
import co.firstorderlabs.coinbaseml.fakebase.TestData.OrdersData
import co.firstorderlabs.coinbaseml.fakebase.TestData.RequestsData._
import co.firstorderlabs.coinbaseml.fakebase.utils.OrderUtils
import co.firstorderlabs.common.currency.Configs.ProductPrice
import co.firstorderlabs.common.currency.Configs.ProductPrice.{ProductVolume, QuoteVolume}
import co.firstorderlabs.common.protos.events.{SimulationId => _, _}
import co.firstorderlabs.common.protos.fakebase.{BuyLimitOrderRequest, BuyMarketOrderRequest, CancellationRequest, SellLimitOrderRequest, SellMarketOrderRequest, StepRequest, Wallets => _}
import co.firstorderlabs.common.types.Events.{SellOrderRequest, _}
import co.firstorderlabs.common.types.Types.SimulationId
import org.scalatest.funspec.AnyFunSpec

class AccountTest extends AnyFunSpec {
  Configs.testMode = true
  describe("Account") {
    it("Wallets should be updated accordingly when an order is placed") {
      List(
        buyLimitOrderRequest _,
        buyMarketOrderRequest _,
        sellLimitOrderRequest _,
        sellMarketOrderRequest _
      ).foreach { orderRequest =>
        val simulationId =
          getResult(Exchange.start(simulationStartRequest)).simulationId.get
        val simulationState =
          SimulationState.getOrFail(simulationId)
        implicit val walletsState = simulationState.accountState.walletsState
        implicit val matchingEngineState = simulationState.matchingEngineState

        val orderFuture = orderRequest(simulationId) match {
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
        buyLimitOrderRequest _,
        buyMarketOrderRequest _,
        sellLimitOrderRequest _,
        sellMarketOrderRequest _
      ).foreach { orderRequest =>
        val simulationId =
          getResult(Exchange.start(simulationStartRequest)).simulationId.get
        val simulationState =
          SimulationState.getOrFail(simulationId)
        implicit val accountState = simulationState.accountState
        implicit val walletsState = simulationState.accountState.walletsState

        val orderFuture = orderRequest(simulationId) match {
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
          case order: BuyOrderEvent =>
            assert(quoteWallet.holds equalTo order.holds)
          case order: SellOrderEvent =>
            assert(productWallet.holds equalTo order.holds)
        }

        Account.cancelOrder(
          new CancellationRequest(
            order.orderId,
            simulationId = Some(simulationId)
          )
        )

        val stepRequest = buildStepRequest(simulationId)
        Exchange.step(stepRequest)

        implicit val orderBookState =
          simulationState.matchingEngineState.getOrderBookState(order.side)
        assert(accountState.placedOrders(order.orderId).orderStatus.isdone)
        assert(accountState.placedOrders(order.orderId).doneReason.iscanceled)
        assert(
          OrderBook
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
      List(buyLimitOrderRequest _, sellLimitOrderRequest _).foreach {
        orderRequest =>
          val simulationId =
            getResult(Exchange.start(simulationStartRequest)).simulationId.get
          val simulationState =
            SimulationState.getOrFail(simulationId)
          implicit val walletsState = simulationState.accountState.walletsState

          val orderFuture = orderRequest(simulationId) match {
            case orderRequest: BuyLimitOrderRequest =>
              Account.placeBuyLimitOrder(orderRequest)
            case orderRequest: SellLimitOrderRequest =>
              Account.placeSellLimitOrder(orderRequest)
          }

          val order = getResult(orderFuture)

          Exchange.step(buildStepRequest(simulationId))

          implicit val orderBookState =
            simulationState.matchingEngineState.getOrderBookState(order.side)
          assert(
            OrderBook
              .getOrderByOrderId(order.orderId)
              .isDefined
          )

          val cancellationRequest = new CancellationRequest(order.orderId, Some(simulationId))
          val cancellation = getResult(Account.cancelOrder(cancellationRequest))

          assert(cancellation.orderId == order.orderId)

          val stepRequest = buildStepRequest(simulationId)
          Exchange.step(stepRequest)

          assert(
            OrderBook
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
      List(buyLimitOrderRequest _, sellLimitOrderRequest _).foreach {
        orderRequestFunc =>
          val simulationId =
            getResult(Exchange.start(simulationStartRequest)).simulationId.get
          val simulationState =
            SimulationState.getOrFail(simulationId)
          implicit val accountState = simulationState.accountState
          implicit val walletsState = simulationState.accountState.walletsState
          implicit val simulationMetadata = simulationState.simulationMetadata

          val orderRequest = orderRequestFunc(simulationId)
          val orderFuture = orderRequest match {
            case orderRequest: BuyLimitOrderRequest =>
              Account.placeBuyLimitOrder(orderRequest)
            case orderRequest: SellLimitOrderRequest =>
              Account.placeSellLimitOrder(orderRequest)
          }

          val order = getResult(orderFuture)

          val stepRequest = new StepRequest(simulationId = Some(simulationId), insertOrders = orderRequest match {
            case _: BuyLimitOrderRequest =>
              OrdersData.insertSellOrders(
                buyLimitOrderRequest(simulationId).price + new ProductPrice(
                  Right("100.00")
                )
              )
            case _: SellLimitOrderRequest =>
              OrdersData.insertBuyOrders(
                sellLimitOrderRequest(simulationId).price
              )
          })

          Exchange.step(stepRequest)

          order match {
            case order: BuyLimitOrder => {
              assert(
                order.price < OrderBook
                  .minPrice(
                    simulationState.matchingEngineState.sellOrderBookState
                  )
                  .get
              )
            }
            case order: SellLimitOrder =>
              assert(
                order.price > OrderBook
                  .maxPrice(
                    simulationState.matchingEngineState.buyOrderBookState
                  )
                  .get
              )
          }

          val openOrder =
            accountState.placedOrders.get(order.orderId).get match {
              case openOrder: LimitOrderEvent => openOrder
            }

          val orderBookState = simulationState.matchingEngineState
            .getOrderBookState(openOrder.side)
          assert(
            orderBookState.orderIdLookup.get(openOrder.orderId).isDefined
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
      List(buyLimitOrderRequest _, sellLimitOrderRequest _).foreach {
        orderRequestFunc =>
          val simulationId =
            getResult(Exchange.start(simulationStartRequest)).simulationId.get
          val simulationState =
            SimulationState.getOrFail(simulationId)
          val matchingEngineState = simulationState.matchingEngineState
          implicit val accountState = simulationState.accountState
          implicit val walletsState = simulationState.accountState.walletsState
          implicit val simulationMetadata = simulationState.simulationMetadata

          val orderRequest = orderRequestFunc(simulationId)
          val stepRequest = new StepRequest(simulationId = Some(simulationId), insertOrders = orderRequest match {
            case _: BuyLimitOrderRequest =>
              OrdersData.insertSellOrders(
                buyLimitOrderRequest(simulationId).price - new ProductPrice(
                  Right("100.00")
                ),
                new ProductVolume(Right("0.5"))
              )
            case _: SellLimitOrderRequest =>
              OrdersData.insertBuyOrders(
                sellLimitOrderRequest(simulationId).price + new ProductPrice(
                  Right("200.00")
                ),
                new ProductVolume(Right("0.5"))
              )
          })

          Exchange.step(stepRequest)

          implicit val orderBookState = orderRequest match {
            case _: BuyOrderRequest  => matchingEngineState.sellOrderBookState
            case _: SellOrderRequest => matchingEngineState.buyOrderBookState
          }
          val expectedMatchPrices = TestUtils.getOrderBookPrices(2)

          val orderFuture = orderRequest match {
            case orderRequest: BuyLimitOrderRequest =>
              Account.placeBuyLimitOrder(orderRequest)
            case orderRequest: SellLimitOrderRequest =>
              Account.placeSellLimitOrder(orderRequest)
          }

          val order = getResult(orderFuture)

          val productWallet = Wallets.getWallet(ProductVolume)
          val quoteWallet = Wallets.getWallet(QuoteVolume)

          Exchange.step(buildStepRequest(simulationId))

          val doneOrder =
            accountState.placedOrders.get(order.orderId).get match {
              case doneOrder: LimitOrderEvent => doneOrder
            }

          val matchEvents = accountState.matches(doneOrder.orderId)

          // Match prices are better than limit prices and best prices from order book were obtained
          doneOrder match {
            case doneOrder: BuyLimitOrder => {
              matchEvents.foreach(m => assert(m.price <= doneOrder.price))
              matchEvents.foreach(m =>
                assert(
                  m.price <= OrderBook
                    .minPrice(matchingEngineState.sellOrderBookState)
                    .get
                )
              )
            }
            case doneOrder: SellLimitOrder => {
              matchEvents.foreach(m => assert(m.price >= doneOrder.price))
              matchEvents.foreach(m =>
                assert(
                  m.price >= OrderBook
                    .maxPrice(matchingEngineState.buyOrderBookState)
                    .get
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
      List(buyLimitOrderRequest _, sellLimitOrderRequest _).foreach {
        orderRequestFunc =>
          val simulationId =
            getResult(Exchange.start(simulationStartRequest)).simulationId.get
          val simulationState =
            SimulationState.getOrFail(simulationId)
          val accountState = simulationState.accountState
          val simulationMetadata = simulationState.simulationMetadata
          implicit val matchingEngineState = simulationState.matchingEngineState
          implicit val walletsState = accountState.walletsState

          val initialOrderBookVolume = new ProductVolume(Right("0.001"))

          val orderRequest = orderRequestFunc(simulationId)
          val stepRequest = new StepRequest(simulationId = Some(simulationId), insertOrders = orderRequest match {
            case orderRequest: BuyLimitOrderRequest =>
              Seq(
                new SellLimitOrder(
                  OrderUtils.generateOrderId,
                  OrderStatus.received,
                  orderRequest.price - OrdersData.priceDelta,
                  ProductPrice.productId,
                  OrderSide.sell,
                  initialOrderBookVolume,
                  simulationMetadata.currentTimeInterval.startTime
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
                  simulationMetadata.currentTimeInterval.startTime
                )
              )
          })

          Exchange.step(stepRequest)

          val (takerOrderBookState, makerOrderBookState) = orderRequest match {
            case _: BuyOrderRequest  => (matchingEngineState.sellOrderBookState, matchingEngineState.buyOrderBookState)
            case _: SellOrderRequest => (matchingEngineState.buyOrderBookState, matchingEngineState.sellOrderBookState)
          }
          val expectedMatchPrices = TestUtils.getOrderBookPrices(1)(takerOrderBookState)

          val orderFuture = orderRequest match {
            case orderRequest: BuyLimitOrderRequest =>
              Account.placeBuyLimitOrder(orderRequest)
            case orderRequest: SellLimitOrderRequest =>
              Account.placeSellLimitOrder(orderRequest)
          }

          val order = getResult(orderFuture)

          Exchange.step(buildStepRequest(simulationId))

          val openOrder =
            accountState.placedOrders.get(order.orderId).get match {
              case openOrder: LimitOrderEvent => openOrder
            }

          val matchEvents = accountState.matches(openOrder.orderId)
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
            OrderBook
              .getOrderByOrderId(order.orderId)(makerOrderBookState)
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
      val buyLimitOrderRequest = (simulationId: SimulationId) =>
        new BuyLimitOrderRequest(
          new ProductPrice(Right("200.00")),
          ProductPrice.productId,
          new ProductVolume(Right("10.000000")),
          false,
          simulationId = Some(simulationId)
        )

      val sellLimitOrderRequest = (simulationId: SimulationId) =>
        new SellLimitOrderRequest(
          new ProductPrice(Right("50.00")),
          ProductPrice.productId,
          new ProductVolume(Right("10.000000")),
          false,
          simulationId = Some(simulationId)
        )

      List(
        buyLimitOrderRequest,
        buyMarketOrderRequest _,
        sellLimitOrderRequest,
        sellMarketOrderRequest _
      ).foreach { orderRequestFunc =>
        val simulationId =
          getResult(Exchange.start(simulationStartRequest)).simulationId.get
        val simulationState =
          SimulationState.getOrFail(simulationId)
        val accountState = simulationState.accountState
        implicit val walletsState = accountState.walletsState
        implicit val simulationMetadata = simulationState.simulationMetadata

        val orderRequest = orderRequestFunc(simulationId)
        val stepRequest = new StepRequest(simulationId = Some(simulationId), insertOrders = orderRequest match {
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

        Exchange.step(buildStepRequest(simulationId))

        val cancelledOrder = accountState.placedOrders.get(order.orderId).get

        val matchEvents = accountState.matches(cancelledOrder.orderId)
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
      List(buyMarketOrderRequest _, sellMarketOrderRequest _).foreach {
        orderRequestFunc =>
          val simulationId =
            getResult(Exchange.start(simulationStartRequest)).simulationId.get
          val simulationState =
            SimulationState.getOrFail(simulationId)
          val accountState = simulationState.accountState
          val matchingEngineState = simulationState.matchingEngineState
          implicit val walletsState = accountState.walletsState
          implicit val simulationMetadata = simulationState.simulationMetadata

          val orderRequest = orderRequestFunc(simulationId)
          val stepRequest = new StepRequest(simulationId = Some(simulationId), insertOrders = orderRequest match {
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

          implicit val orderBookState = orderRequest match {
            case _: BuyOrderRequest  => matchingEngineState.sellOrderBookState
            case _: SellOrderRequest => matchingEngineState.buyOrderBookState
          }
          val expectedMatchPrices = TestUtils.getOrderBookPrices(2)

          val orderFuture = orderRequest match {
            case orderRequest: BuyMarketOrderRequest =>
              Account.placeBuyMarketOrder(orderRequest)
            case orderRequest: SellMarketOrderRequest =>
              Account.placeSellMarketOrder(orderRequest)
          }

          val order = getResult(orderFuture)

          Exchange.step(buildStepRequest(simulationId))

          val doneOrder = accountState.placedOrders.get(order.orderId).get
          val matchEvents = accountState.matches(order.orderId)
          val matchPrices = matchEvents.map(m => m.price)

          assert(doneOrder.orderStatus.isdone)
          assert(doneOrder.doneReason.isfilled)
          assert(
            OrderBook
              .getOrderByOrderId(doneOrder.orderId)(
                matchingEngineState.getOrderBookState(doneOrder.side)
              )
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
      List(buyMarketOrderRequest _, sellMarketOrderRequest _).foreach {
        orderRequestFunc =>
          val simulationId =
            getResult(Exchange.start(simulationStartRequest)).simulationId.get
          val simulationState =
            SimulationState.getOrFail(simulationId)
          implicit val accountState = simulationState.accountState
          implicit val walletsState = accountState.walletsState

          val orderRequest = orderRequestFunc(simulationId)
          val orderFuture = orderRequest match {
            case orderRequest: BuyMarketOrderRequest =>
              Account.placeBuyMarketOrder(orderRequest)
            case orderRequest: SellMarketOrderRequest =>
              Account.placeSellMarketOrder(orderRequest)
          }

          val order = getResult(orderFuture)

          Exchange.step(buildStepRequest(simulationId))

          val cancelledOrder = accountState.placedOrders.get(order.orderId).get

          assert(cancelledOrder.orderStatus.isdone)
          assert(cancelledOrder.doneReason.iscanceled)
          assert(
            OrderBook
              .getOrderByOrderId(cancelledOrder.orderId)(
                simulationState.matchingEngineState
                  .getOrderBookState(cancelledOrder.side)
              )
              .isEmpty
          )

          val productWallet = Wallets.getWallet(ProductVolume)
          val quoteWallet = Wallets.getWallet(QuoteVolume)

          assert(
            productWallet.balance equalTo simulationStartRequest.initialProductFunds
          )
          assert(productWallet.holds equalTo ProductVolume.zeroVolume)
          assert(
            quoteWallet.balance equalTo simulationStartRequest.initialQuoteFunds
          )
          assert(quoteWallet.holds equalTo QuoteVolume.zeroVolume)
      }
    }

    it(
      "A taker order that matches with an order from the same account should be cancelled."
    ) {
      val buyLimitOrderRequest = (simulationId: SimulationId) =>
        new BuyLimitOrderRequest(
          new ProductPrice(Right("200.00")),
          ProductPrice.productId,
          new ProductVolume(Right("10.000000")),
          false,
          Some(Duration.ofDays(10)),
          simulationId = Some(simulationId)
        )

      val sellLimitOrderRequest = (simulationId: SimulationId) =>
        new SellLimitOrderRequest(
          new ProductPrice(Right("50.00")),
          ProductPrice.productId,
          new ProductVolume(Right("10.000000")),
          false,
          Some(Duration.ofDays(10)),
          simulationId = Some(simulationId)
        )

      List(
        buyLimitOrderRequest,
        buyMarketOrderRequest _,
        sellLimitOrderRequest,
        sellMarketOrderRequest _
      ).foreach { orderRequestFunc =>
        val simulationId =
          getResult(Exchange.start(simulationStartRequest)).simulationId.get
        val simulationState =
          SimulationState.getOrFail(simulationId)
        val accountState = simulationState.accountState
        val matchingEngineState = simulationState.matchingEngineState
        implicit val walletsState = accountState.walletsState

        val orderRequest = orderRequestFunc(simulationId)
        val makerOrderFuture = orderRequest match {
          case _: BuyOrderRequest =>
            Account.placeSellLimitOrder(sellLimitOrderRequest(simulationId))
          case _: SellOrderRequest =>
            Account.placeBuyLimitOrder(buyLimitOrderRequest(simulationId))
        }

        val makerOrder = getResult(makerOrderFuture)

        Exchange.step(buildStepRequest(simulationId))

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

        Exchange.step(buildStepRequest(simulationId))

        assert(accountState.placedOrders(takerOrder.orderId).orderStatus.isdone)
        assert(
          accountState.placedOrders(takerOrder.orderId).doneReason.iscanceled
        )
        assert(accountState.matches(takerOrder.orderId).isEmpty)
        assert(
          OrderBook
            .getOrderByOrderId(takerOrder.orderId)(
              matchingEngineState.getOrderBookState(takerOrder.side)
            )
            .isEmpty
        )
        assert(
          OrderBook
            .getOrderByOrderId(makerOrder.orderId)(
              matchingEngineState.getOrderBookState(makerOrder.side)
            )
            .get
            .remainingSize equalTo makerOrder.size
        )

        val productWallet = Wallets.getWallet(ProductVolume)
        val quoteWallet = Wallets.getWallet(QuoteVolume)

        assert(
          productWallet.balance equalTo simulationStartRequest.initialProductFunds
        )
        assert(
          quoteWallet.balance equalTo simulationStartRequest.initialQuoteFunds
        )
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

    it("Wallet state should be properly restored from snapshot.") {
      val simulationId =
        getResult(Exchange.start(simulationStartRequestWarmup)).simulationId.get
      val simulationState =
        SimulationState.getOrFail(simulationId)
      val simulationMetadata = simulationState.simulationMetadata
      val walletsSnapshot =
        simulationState.accountState.walletsState.createSnapshot(simulationMetadata)
      for (_ <- 1 to 2) {
        val stepRequest = new StepRequest(
          insertOrders = OrdersData.insertSellOrders(
            new ProductPrice(Right("1000.00")),
            new ProductVolume(Right("0.5"))
          )(simulationMetadata),
          simulationId = Some(simulationId)
        )
        Exchange.step(stepRequest)
        Account.placeBuyMarketOrder(buyMarketOrderRequest(simulationId))
        Exchange.step(buildStepRequest(simulationId))
        Exchange.reset(simulationId.toObservationRequest)
      }

      val restoredSimulationState = SimulationState.getOrFail(simulationId)
      val restoredSimulationMetadata = restoredSimulationState.simulationMetadata

      assert(
        walletsSnapshot == restoredSimulationState.accountState.walletsState.createSnapshot(restoredSimulationMetadata)
      )
      assert(
        walletsSnapshot
          .walletsMap(ProductVolume.currency)
          .balance
          .asInstanceOf[
            ProductVolume
          ] equalTo simulationStartRequest.initialProductFunds
      )
      assert(
        walletsSnapshot
          .walletsMap(ProductVolume.currency)
          .holds
          .asInstanceOf[ProductVolume] equalTo ProductVolume.zeroVolume
      )
      assert(
        walletsSnapshot
          .walletsMap(QuoteVolume.currency)
          .balance
          .asInstanceOf[
            QuoteVolume
          ] equalTo simulationStartRequest.initialQuoteFunds
      )
      assert(
        walletsSnapshot
          .walletsMap(QuoteVolume.currency)
          .holds
          .asInstanceOf[QuoteVolume] equalTo QuoteVolume.zeroVolume
      )
    }
  }
}

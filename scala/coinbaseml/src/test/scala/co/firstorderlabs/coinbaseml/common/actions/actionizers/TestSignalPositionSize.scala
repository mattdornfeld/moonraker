package co.firstorderlabs.coinbaseml.common.actions.actionizers

import java.time.{Duration, Instant}

import co.firstorderlabs.coinbaseml.common.actions.actionizers.Actions.{BuyMarketOrderTransaction, NoTransaction, SellMarketOrderTransaction}
import co.firstorderlabs.coinbaseml.common.protos.{ActionRequest, Actionizer}
import co.firstorderlabs.coinbaseml.common.rewards.ReturnRewardStrategy
import co.firstorderlabs.coinbaseml.common.utils.TestUtils.advanceExchange
import co.firstorderlabs.coinbaseml.fakebase.TestData.RequestsData.{observationRequest, simulationStartRequest, simulationStartRequestWarmup}
import co.firstorderlabs.common.currency.Price.BtcUsdPrice.{ProductVolume, QuoteVolume}
import co.firstorderlabs.common.protos.fakebase.{BuyMarketOrder, Liquidity, SellMarketOrder, SimulationStartRequest, StepRequest}
import co.firstorderlabs.coinbaseml.fakebase.{Account, Configs, Exchange}
import org.scalatest.funspec.AnyFunSpec

class TestSignalPositionSize extends AnyFunSpec {
  Configs.testMode = true
  describe("SignalPositionSize") {
    it(
      "The SignalPositionSize Actionizer should create a NoTransaction Action when a value" +
        "between 0 and 1 is in the 0th element of the input vector."
    ) {
      List(0.3, 0.5, 0.9).foreach { entrySignal =>
        Exchange.start(simulationStartRequestWarmup)
        val action = SignalPositionSize.construct(List(entrySignal, 1.0))
        assert(action.isInstanceOf[NoTransaction])
        val order = action.execute
        assert(order.isEmpty)
        assert(Account.placedOrders.isEmpty)
      }

    }

    it(
      "When the actorInput is (1.0, positionSizeFraction) the SignalPositionSize Actionizer should place a " +
        "BuyMarketOrder that rebalances the portfolio value so that ratio of the portfolio's productValue / totalValue" +
        "is equal to positionSizeFraction."
    ) {
      List(0.3, 0.9, 1.0).foreach { positionSizeFraction =>
        Exchange.start(simulationStartRequestWarmup)
        val action =
          SignalPositionSize.construct(List(1.0, positionSizeFraction))
        assert(action.isInstanceOf[BuyMarketOrderTransaction])
        val order = action.execute.get
        assert(order.orderStatus.isreceived)
        assert(Account.placedOrders.contains(order.orderId))
        val expectedFunds = new QuoteVolume(
          Right(
            (ReturnRewardStrategy.currentPortfolioValue * positionSizeFraction).toString
          )
        ).subtractFees(Liquidity.taker)

        order match {
          case order: BuyMarketOrder =>
            assert(
              expectedFunds equalTo order.funds
            )
        }
      }
    }

    it("When the actorInput is (1.0, positionSizeFraction) and the portfolio has a productValueRatio in excess of " +
      "positionSizeFraction the SignalPositionSize Actionizer should execute a SellMarketOrder that will decrease the " +
      "productValueRatio to positionSizeFraction.") {
      val initialProductVolume = new ProductVolume(Right("0.100000"))
      val simulationStartRequest = new SimulationStartRequest(
        Instant.parse("2019-11-20T19:20:50.63Z"),
        Instant.parse("2019-11-20T19:25:50.63Z"),
        Some(Duration.ofSeconds(30)),
        3,
        initialProductVolume,
        new QuoteVolume(Right("00.00")),
        snapshotBufferSize = 3,
        observationRequest = Some(observationRequest),
      )
      List(0.1, 0.3, 0.9).foreach { positionSizeFraction =>
        Exchange.start(simulationStartRequest)
        advanceExchange
        val action = SignalPositionSize.construct(List(1.0, positionSizeFraction))
        assert(action.isInstanceOf[SellMarketOrderTransaction])
        val order = action.execute.get
        assert(order.orderStatus.isreceived)
        assert(Account.placedOrders.contains(order.orderId))

        val expecteOrderSize = initialProductVolume * Right(1 - positionSizeFraction)
        order match {
          case order: SellMarketOrder => assert(expecteOrderSize equalTo order.size)
        }
      }
    }

    it("When signalStength==0.0 the SignalPositionSize Actionizer should execute a SellMarketOrder that will close all" +
      "open positions.") {
      Exchange.start(simulationStartRequestWarmup)
      val action = SignalPositionSize.construct(List(0.0, 0.5))
      assert(action.isInstanceOf[SellMarketOrderTransaction])
      val order = action.execute.get
      assert(order.orderStatus.isreceived)
      assert(Account.placedOrders.contains(order.orderId))

      order match {
        case order: SellMarketOrder => assert(simulationStartRequestWarmup.initialProductFunds equalTo order.size)
      }
    }

    it("When signalStength==0.0 and the portfolio contains no product the SignalPositionSize Actionizer should construct" +
      "a NoTransaction Action.") {
      val simulationStartRequest = new SimulationStartRequest(
        Instant.parse("2019-11-20T19:20:50.63Z"),
        Instant.parse("2019-11-20T19:25:50.63Z"),
        Some(Duration.ofSeconds(30)),
        3,
        new ProductVolume(Right("0.000000")),
        new QuoteVolume(Right("1000.00")),
        snapshotBufferSize = 3,
        observationRequest = Some(observationRequest),
      )

      Exchange.start(simulationStartRequest)
      val action = SignalPositionSize.construct(List(0.0, 0.5))
      val order = action.execute
      assert(action.isInstanceOf[NoTransaction])
      assert(order.isEmpty)
    }

  }
    it("When the actorInput is (1.0, positionSizeFraction) and the portfolio has a productValueRatio within " +
      "SignalPositionSize.minimumValueDifferentialFraction of positionSizeFraction the SignalPositionSize Actionizer " +
      "should construct a NoTransaction Action.") {
      val simulationStartRequest = new SimulationStartRequest(
        Instant.parse("2019-11-20T19:20:50.63Z"),
        Instant.parse("2019-11-20T19:25:50.63Z"),
        Some(Duration.ofSeconds(30)),
        3,
        new ProductVolume(Right("0.100000")),
        new QuoteVolume(Right("00.00")),
        snapshotBufferSize = 3,
        observationRequest = Some(observationRequest),
      )

      Exchange.start(simulationStartRequest)
      advanceExchange
      val positionSizeFraction = 0.96
      val action = SignalPositionSize.construct(List(1.0, positionSizeFraction))
      val order = action.execute
      assert((1.0 - positionSizeFraction) < SignalPositionSize.minimumValueDifferentialFraction)
      assert(action.isInstanceOf[NoTransaction])
      assert(order.isEmpty)
    }

  it("The SignalPositionSize Actionizer should be called when the appropriate ActionRequest is passed in with the " +
    "Exchange StepRequest.") {
    Exchange.start(simulationStartRequest)
    val stepRequest = StepRequest(
      actionRequest=Some(ActionRequest(List(1.0, 1.0), Actionizer.SignalPositionSize))
    )
    Exchange.step(stepRequest)
    val expectedFunds = simulationStartRequest.initialQuoteFunds.subtractFees(Liquidity.taker)
    assert(Account.placedOrders.size == 1)
    val order = Account.placedOrders.values.toList(0)
    assert(order.orderStatus.isreceived)
    order match {
      case order: BuyMarketOrder => assert(expectedFunds equalTo order.funds)
    }
  }
}

package co.firstorderlabs.coinbaseml.common.actions.actionizers

import co.firstorderlabs.coinbaseml.common.Configs.testMode
import co.firstorderlabs.coinbaseml.common.actions.actionizers.Actions.{BuyMarketOrderTransaction, NoTransaction, SellMarketOrderTransaction}
import co.firstorderlabs.coinbaseml.common.rewards.ReturnRewardStrategy
import co.firstorderlabs.coinbaseml.common.utils.TestUtils.advanceExchange
import co.firstorderlabs.coinbaseml.common.utils.Utils.getResult
import co.firstorderlabs.coinbaseml.fakebase.TestData.RequestsData.{observationRequest, simulationStartRequest, simulationStartRequestWarmup}
import co.firstorderlabs.coinbaseml.fakebase.{Configs, Exchange, SimulationState}
import co.firstorderlabs.common.currency.Price.BtcUsdPrice.{ProductVolume, QuoteVolume}
import co.firstorderlabs.common.protos.actionizers.{Actionizer, SignalPositionSizeConfigs}
import co.firstorderlabs.common.protos.environment.ActionRequest
import co.firstorderlabs.common.protos.events.{BuyMarketOrder, Liquidity, SellMarketOrder}
import co.firstorderlabs.common.protos.fakebase.{SimulationStartRequest, StepRequest}
import co.firstorderlabs.common.types.Utils.OptionUtils
import org.scalatest.funspec.AnyFunSpec

import java.time.{Duration, Instant}

class TestSignalPositionSize extends AnyFunSpec {
  testMode = true

  val simulationStartRequest = simulationStartRequestWarmup
    .update(
      _.actionRequest := ActionRequest(actionizer =
        Actionizer.SignalPositionSize
      )
    )

  describe("SignalPositionSize") {
    it(
      "The SignalPositionSize Actionizer should create a NoTransaction Action when a value" +
        "in noTransactionRange is in the 0th element of the input vector."
    ) {
      SignalPositionSize.noTransactionRange.iterator(0.1).foreach {
        entrySignal =>
          val simulationInfo =
            getResult(Exchange.start(simulationStartRequest))
          implicit val simulationState =
            SimulationState.getOrFail(simulationInfo.simulationId.get)
          val accountState = simulationState.accountState
          val action = SignalPositionSize.updateAndConstruct(List(entrySignal, 1.0))
          assert(action.isInstanceOf[NoTransaction])
          val order = action.execute
          assert(order.isEmpty)
          assert(accountState.placedOrders.isEmpty)
      }
    }

    it(
      "The SignalPositionSize Actionizer should create a SellMarketOrderTransaction Action when a value" +
        "in closeAllPositionsRange is in the 0th element of the input vector."
    ) {
      SignalPositionSize.closeAllPositionsRange.iterator(0.1).foreach {
        entrySignal =>
          val simulationInfo =
            getResult(Exchange.start(simulationStartRequest))
          val simulationState =
            SimulationState.getOrFail(simulationInfo.simulationId.get)
          SignalPositionSize.update(List(entrySignal, 1.0))(simulationState)
          val updatedSimulationState =
            SimulationState.getOrFail(simulationInfo.simulationId.get)
          val action = SignalPositionSize.updateAndConstruct(List(entrySignal, 1.0))(updatedSimulationState)
          assert(action.isInstanceOf[SellMarketOrderTransaction])
      }
    }

    it(
      "The SignalPositionSize Actionizer should create a BuyMarketOrderTransaction Action when a value" +
        "in openNewPositionRange is in the 0th element of the input vector and the 1st element is larger than the " +
        "current portfolio position."
    ) {
      SignalPositionSize.openNewPositionRange.iterator(0.1).foreach {
        entrySignal =>
          val simulationInfo =
            getResult(Exchange.start(simulationStartRequest))
          implicit val simulationState =
            SimulationState.getOrFail(simulationInfo.simulationId.get)
          val action = SignalPositionSize.updateAndConstruct(List(entrySignal, 1.0))
          assert(action.isInstanceOf[BuyMarketOrderTransaction])
      }
    }

    it(
      "When the actorInput is (1.0, positionSizeFraction) the SignalPositionSize Actionizer should place a " +
        "BuyMarketOrder that rebalances the portfolio value so that ratio of the portfolio's productValue / totalValue" +
        "is equal to positionSizeFraction."
    ) {
      List(0.3, 0.9, 1.0).foreach { positionSizeFraction =>
        val simulationInfo =
          getResult(Exchange.start(simulationStartRequest))
        implicit val simulationState =
          SimulationState.getOrFail(simulationInfo.simulationId.get)
        implicit val matchingEngineState = simulationState.matchingEngineState
        val accountState = simulationState.accountState
        val action =
          SignalPositionSize.updateAndConstruct(List(1.0, positionSizeFraction))
        assert(action.isInstanceOf[BuyMarketOrderTransaction])
        val order = action.execute.get
        assert(order.orderStatus.isreceived)
        assert(accountState.placedOrders.contains(order.orderId))
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

    it(
      "When the actorInput is (1.0, positionSizeFraction) and the portfolio has a productValueRatio in excess of " +
        "positionSizeFraction the SignalPositionSize Actionizer should execute a SellMarketOrder that will decrease the " +
        "productValueRatio to positionSizeFraction."
    ) {
      val initialProductVolume = new ProductVolume(Right("0.100000"))
      val simulationStartRequest = new SimulationStartRequest(
        Instant.parse("2019-11-20T19:20:50.63Z"),
        Instant.parse("2019-11-20T19:25:50.63Z"),
        Some(Duration.ofSeconds(30)),
        3,
        initialProductVolume,
        new QuoteVolume(Right("00.00")),
        actionRequest = ActionRequest(actionizer=Actionizer.SignalPositionSize).some,
        observationRequest = Some(observationRequest)
      )
      List(0.1, 0.3, 0.9).foreach { positionSizeFraction =>
        val simulationInfo = getResult(Exchange.start(simulationStartRequest))
        implicit val simulationState =
          SimulationState.getOrFail(simulationInfo.simulationId.get)
        implicit val simulationMetadata = simulationState.simulationMetadata

        val accountState = simulationState.accountState
        advanceExchange
        val action =
          SignalPositionSize.updateAndConstruct(List(1.0, positionSizeFraction))
        assert(action.isInstanceOf[SellMarketOrderTransaction])
        val order = action.execute.get
        assert(order.orderStatus.isreceived)
        assert(accountState.placedOrders.contains(order.orderId))

        val expecteOrderSize =
          initialProductVolume * Right(1 - positionSizeFraction)
        order match {
          case order: SellMarketOrder => {
            assert(expecteOrderSize equalTo order.size)
          }
        }
      }
    }

    it(
      "When signalStength==0.0 the SignalPositionSize Actionizer should execute a SellMarketOrder that will close all" +
        "open positions."
    ) {
      val simulationInfo =
        getResult(Exchange.start(simulationStartRequest))
      implicit val simulationState =
        SimulationState.getOrFail(simulationInfo.simulationId.get)
      val accountState = simulationState.accountState
      val action = SignalPositionSize.updateAndConstruct(List(0.0, 0.5))
      assert(action.isInstanceOf[SellMarketOrderTransaction])
      val order = action.execute.get
      assert(order.orderStatus.isreceived)
      assert(accountState.placedOrders.contains(order.orderId))

      order match {
        case order: SellMarketOrder =>
          assert(
            simulationStartRequest.initialProductFunds equalTo order.size
          )
      }
    }

    it(
      "When signalStength==0.0 and the portfolio contains no product the SignalPositionSize Actionizer should construct" +
        "a NoTransaction Action."
    ) {
      val simulationStartRequest = new SimulationStartRequest(
        Instant.parse("2019-11-20T19:20:50.63Z"),
        Instant.parse("2019-11-20T19:25:50.63Z"),
        Some(Duration.ofSeconds(30)),
        3,
        new ProductVolume(Right("0.000000")),
        new QuoteVolume(Right("1000.00")),
        actionRequest = ActionRequest(actionizer=Actionizer.SignalPositionSize).some,
        observationRequest = Some(observationRequest),
        stopInProgressSimulations = true
      )

      val simulationInfo = getResult(Exchange.start(simulationStartRequest))
      implicit val simulationState =
        SimulationState.getOrFail(simulationInfo.simulationId.get)
      val action = SignalPositionSize.updateAndConstruct(List(0.0, 0.5))
      val order = action.execute
      assert(action.isInstanceOf[NoTransaction])
      assert(order.isEmpty)
    }

  }
  it(
    "When the actorInput is (1.0, positionSizeFraction) and the portfolio has a productValueRatio within " +
      "SignalPositionSize.minimumValueDifferentialFraction of positionSizeFraction the SignalPositionSize Actionizer " +
      "should construct a NoTransaction Action."
  ) {
    val simulationStartRequest = new SimulationStartRequest(
      Instant.parse("2019-11-20T19:20:50.63Z"),
      Instant.parse("2019-11-20T19:25:50.63Z"),
      Some(Duration.ofSeconds(30)),
      3,
      new ProductVolume(Right("0.100000")),
      new QuoteVolume(Right("00.00")),
      actionRequest = ActionRequest(actionizer=Actionizer.SignalPositionSize).some,
      observationRequest = Some(observationRequest),
      stopInProgressSimulations = true
    )

    val simulationInfo = getResult(Exchange.start(simulationStartRequest))
    implicit val simulationState =
      SimulationState.getOrFail(simulationInfo.simulationId.get)
    implicit val simulationMetadata = simulationState.simulationMetadata
    advanceExchange
    val positionSizeFraction = 0.96
    val action = SignalPositionSize.updateAndConstruct(List(1.0, positionSizeFraction))
    val order = action.execute
    assert(
      (1.0 - positionSizeFraction) < SignalPositionSize.minimumValueDifferentialFraction
    )
    assert(action.isInstanceOf[NoTransaction])
    assert(order.isEmpty)
  }

  it(
    "The SignalPositionSize Actionizer should be called when the appropriate ActionRequest is passed in with the " +
      "Exchange StepRequest."
  ) {
    val simulationInfo = getResult(Exchange.start(simulationStartRequest))
    val accountState =
      SimulationState.getAccountStateOrFail(simulationInfo.simulationId.get)
    val stepRequest = StepRequest(
      actionRequest = Some(
        ActionRequest(
          List(1.0, 1.0),
          Actionizer.SignalPositionSize,
          simulationInfo.simulationId
        )
      ),
      simulationId = simulationInfo.simulationId
    )
    Exchange.step(stepRequest)
    val expectedFunds =
      simulationStartRequest.initialQuoteFunds.subtractFees(Liquidity.taker)
    assert(accountState.placedOrders.size == 1)
    val order = accountState.placedOrders.values.toList(0)
    assert(order.orderStatus.isdone)
    order match {
      case order: BuyMarketOrder => assert(expectedFunds equalTo order.funds)
    }
  }
}

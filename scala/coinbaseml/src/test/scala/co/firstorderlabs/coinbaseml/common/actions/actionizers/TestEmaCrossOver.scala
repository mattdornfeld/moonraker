package co.firstorderlabs.coinbaseml.common.actions.actionizers

import java.time.{Duration, Instant}

import co.firstorderlabs.coinbaseml.common.EnvironmentState
import co.firstorderlabs.coinbaseml.common.actions.actionizers.Actions.{BuyMarketOrderTransaction, SellMarketOrderTransaction}
import co.firstorderlabs.coinbaseml.common.utils.TestUtils.DoubleUtils
import co.firstorderlabs.coinbaseml.common.utils.Utils.FutureUtils
import co.firstorderlabs.coinbaseml.fakebase._
import co.firstorderlabs.common.currency.Configs.ProductPrice
import co.firstorderlabs.common.currency.Configs.ProductPrice.{ProductVolume, QuoteVolume}
import co.firstorderlabs.common.protos.environment.{ActionRequest, Featurizer, ObservationRequest, Actionizer => ActionizerProto}
import co.firstorderlabs.common.protos.events.{BuyLimitOrder, OrderSide}
import co.firstorderlabs.common.protos.fakebase.{BuyMarketOrderRequest, SellMarketOrderRequest, SimulationStartRequest, StepRequest}
import org.scalatest.funspec.AnyFunSpec

class TestEmaCrossOver extends AnyFunSpec {
  Configs.testMode = true
  val productVolume = new ProductVolume(Right("1.00"))
  val actionRequest = new ActionRequest(
    actionizer = ActionizerProto.EmaCrossOver
  )
  val updateOnlyActionRequest = new ActionRequest(
    actionizer = ActionizerProto.EmaCrossOver,
    actorOutput = Seq(-1)
  )
  val simulationStartRequestWarmup = new SimulationStartRequest(
    Instant.parse("2019-11-20T00:00:00.00Z"),
    Instant.parse("2019-11-21T00:00:00.00Z"),
    Some(Duration.ofSeconds(30)),
    3,
    new ProductVolume(Right("0.000000")),
    new QuoteVolume(Right("0.00")),
    actionRequest = Some(actionRequest),
    actionizerConfigs = Map("fastWindowSize" -> 2.0, "slowWindowSize" -> 4.0),
    observationRequest =
      Some(new ObservationRequest(featurizer = Featurizer.NoOp)),
    stopInProgressSimulations = true
  )

  def getActionizerState(implicit
      environmentState: EnvironmentState
  ): (Double, Double) = {
    (
      environmentState.actionizerState.getState("emaFast"),
      environmentState.actionizerState.getState("emaSlow")
    )
  }

  describe("EmaCrossOver") {
    it(
      "Ensure that a BuyMarketOrderTransaction/SellMarketOrderTransaction is placed" +
        "when the mid price increases/decreases, causing the fast ema to cross above,below the" +
        "slow ema."
    ) {
      List(BuyMarketOrderTransaction, SellMarketOrderTransaction).foreach {
        expectedTransaction =>
          val simulationStartRequest = expectedTransaction match {
            case BuyMarketOrderTransaction =>
              simulationStartRequestWarmup.update(
                _.initialQuoteFunds := new QuoteVolume(Right("1000.000000"))
              )
            case SellMarketOrderTransaction =>
              simulationStartRequestWarmup.update(
                _.initialProductFunds := new ProductVolume(Right("1.000000"))
              )
          }
          val simulationId =
            Exchange.start(simulationStartRequest).get.simulationId.get
          implicit val simulationState =
            SimulationState.getOrFail(simulationId)
          val simulationMetadata = simulationState.simulationMetadata
          implicit val environmentState = simulationState.environmentState
          implicit val matchingEngineState = simulationState.matchingEngineState

          val stepRequest = StepRequest(
            actionRequest = Some(updateOnlyActionRequest),
            simulationId = Some(simulationId)
          )

          val buyOrders = TestUtils
            .generateOrdersForRangeOfPrices(
              new ProductPrice(Right("1.00")),
              new ProductPrice(Right("900.00")),
              new ProductPrice(Right("901.00")),
              OrderSide.buy,
              productVolume,
              simulationMetadata.currentTimeInterval.endTime
            )

          val buyCancellations = buyOrders.map { order =>
            order match {
              case order: BuyLimitOrder => order.toCancellation
            }
          }

          val sellOrders = TestUtils
            .generateOrdersForRangeOfPrices(
              new ProductPrice(Right("1.00")),
              new ProductPrice(Right("1000.00")),
              new ProductPrice(Right("1001.00")),
              OrderSide.sell,
              productVolume,
              simulationMetadata.currentTimeInterval.endTime
            )

          Exchange step stepRequest.update(
            _.insertOrders := buyOrders ++ sellOrders
          )

          // Warmup moving averages
          (1 to 100).foreach { _ =>
            Exchange step stepRequest
          }

          val (emaFast1, emaSlow1) = getActionizerState
          assert(emaFast1 ~= emaSlow1)

          // If a BuyMarketOrderTransaction is expected then add a new buy order to the
          // order book to increase the mid price. This will cause the fast ema to cross
          // above the slow ema
          val insertOrders2 = expectedTransaction match {
            case BuyMarketOrderTransaction =>
              TestUtils
                .generateOrdersForRangeOfPrices(
                  new ProductPrice(Right("1.00")),
                  new ProductPrice(Right("950.00")),
                  new ProductPrice(Right("951.00")),
                  OrderSide.buy,
                  productVolume,
                  simulationMetadata.currentTimeInterval.endTime
                )
            case _ => List()
          }

          // If a SellMarketOrderTransaction is expected cancel the orders
          // on the buy order book to decrease the mid price, causing
          // the fast ema to cross below the slow ema
          val insertCancellations = expectedTransaction match {
            case SellMarketOrderTransaction => buyCancellations
            case _                          => Seq()
          }

          Exchange step stepRequest.update(
            _.insertCancellations := insertCancellations,
            _.insertOrders := insertOrders2
          )

          Exchange step stepRequest.update(_.actionRequest := actionRequest)
          Exchange step stepRequest.update(_.actionRequest := actionRequest)

          val orderRequests =
            simulationState.accountState.orderRequests.values.toList

          // Assert there is only one order request in the accountState. This indicates
          // that no transactions are created during the warmup phase or when the
          // updateOnlyActionRequest is passed to step
          assert(orderRequests.size == 1)

          val (emaFast2, emaSlow2) = getActionizerState
          expectedTransaction match {
            case BuyMarketOrderTransaction => {
              assert(emaFast2 > emaSlow2)
              assert(orderRequests(0).isInstanceOf[BuyMarketOrderRequest])
            }
            case SellMarketOrderTransaction => {
              assert(emaFast2 < emaSlow2)
              assert(orderRequests(0).isInstanceOf[SellMarketOrderRequest])
            }
          }
      }
    }
  }
}

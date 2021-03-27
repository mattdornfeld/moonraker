package co.firstorderlabs.coinbaseml.common.featurizers

import co.firstorderlabs.coinbaseml.common.Configs.testMode
import co.firstorderlabs.coinbaseml.common.utils.Utils.{When, getResult}
import co.firstorderlabs.coinbaseml.fakebase.TestData.RequestsData._
import co.firstorderlabs.coinbaseml.fakebase.{Account, Configs, Exchange, SimulationState}
import org.scalatest.funspec.AnyFunSpec

class TestAccountFeaturizer extends AnyFunSpec {
  testMode = true

  describe("AccountFeaturizer") {
    it(
      "The AccountFeaturizer should return a feature vector of the form (quote_balance, quote_holds, product_balance, product_holds"
    ) {
      val simulationInfo = getResult(Exchange.start(simulationStartRequest))
      implicit val simulationState = SimulationState.getOrFail(simulationInfo.simulationId.get)
      List(observationRequest, normalizeObservationRequest).foreach { request =>
        val accountFeatures = AccountVectorizer.construct(request)
        val expectedAccountFeatures = List(
          simulationStartRequest.initialQuoteFunds
            .whenElse(request.normalize)(_.normalize, _.toDouble),
          0.0,
          simulationStartRequest.initialProductFunds
            .whenElse(request.normalize)(_.normalize, _.toDouble),
          0.0,
        )

        assert(expectedAccountFeatures sameElements accountFeatures)
      }

    }

    it(
      "When orders are put on the order book the holds should appear in the feature vector returned from AccountFeaturizer"
    ) {
      val simulationInfo = getResult(Exchange.start(simulationStartRequest))
      implicit val simulationState = SimulationState.getOrFail(simulationInfo.simulationId.get)
      val buyOrder = getResult(Account.placeBuyLimitOrder(buyLimitOrderRequest(simulationInfo.simulationId.get)))
      val sellOrder =
        getResult(Account.placeSellLimitOrder(sellLimitOrderRequest(simulationInfo.simulationId.get)))
      List(observationRequest, normalizeObservationRequest).foreach { request =>
        val accountFeatures = AccountVectorizer.construct(request)
        val expectedAccountFeatures = List(
          simulationStartRequest.initialQuoteFunds
            .whenElse(request.normalize)(_.normalize, _.toDouble),
          buyOrder.holds.whenElse(request.normalize)(_.normalize, _.toDouble),
          simulationStartRequest.initialProductFunds
            .whenElse(request.normalize)(_.normalize, _.toDouble),
          sellOrder.holds.whenElse(request.normalize)(_.normalize, _.toDouble),
        )

        assert(expectedAccountFeatures sameElements accountFeatures)
      }
    }
  }
}

package co.firstorderlabs.coinbaseml.common.featurizers

import co.firstorderlabs.coinbaseml.common.utils.Utils.{When, getResult}
import co.firstorderlabs.coinbaseml.fakebase.TestData.RequestsData._
import co.firstorderlabs.coinbaseml.fakebase.{Account, Configs, Exchange}
import org.scalatest.funspec.AnyFunSpec

class TestAccountFeaturizer extends AnyFunSpec {
  Configs.testMode = true

  describe("AccountFeaturizer") {
    it(
      "The AccountFeaturizer should return a feature vector of the form (quote_balance, quote_holds, product_balance, product_holds"
    ) {
      Exchange.start(simulationStartRequest)
      List(observationRequest, normalizeObservationRequest).foreach { request =>
        val accountFeatures = AccountFeaturizer.construct(request)
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
      Exchange.start(simulationStartRequest)
      val buyOrder = getResult(Account.placeBuyLimitOrder(buyLimitOrderRequest))
      val sellOrder =
        getResult(Account.placeSellLimitOrder(sellLimitOrderRequest))
      List(observationRequest, normalizeObservationRequest).foreach { request =>
        val accountFeatures = AccountFeaturizer.construct(request)
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

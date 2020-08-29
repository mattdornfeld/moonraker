package co.firstorderlabs.common.featurizers

import co.firstorderlabs.common.utils.Utils.getResult
import co.firstorderlabs.fakebase.TestData.RequestsData._
import co.firstorderlabs.fakebase.{Account, Configs, Exchange}
import org.scalatest.funspec.AnyFunSpec

class TestAccountFeaturizer extends AnyFunSpec {
  Configs.testMode = true

  describe("AccountFeaturizer") {
    it("The AccountFeaturizer should return a feature vector of the form (quote_balance, quote_holds, product_balance, product_holds") {
      Exchange.start(simulationStartRequest)
      val accountFeatures = AccountFeaturizer.construct(observationRequest)
      val expectedAccountFeatures = List(
        simulationStartRequest.initialQuoteFunds.normalize,
        0.0,
        simulationStartRequest.initialProductFunds.normalize,
        0.0,
      )

      assert(expectedAccountFeatures sameElements accountFeatures)
    }

    it("When orders are put on the order book the holds should appear in the feature vector returned from AccountFeaturizer") {
      Exchange.start(simulationStartRequest)
      val buyOrder = getResult(Account.placeBuyLimitOrder(buyLimitOrderRequest))
      val sellOrder = getResult(Account.placeSellLimitOrder(sellLimitOrderRequest))
      val accountFeatures = AccountFeaturizer.construct(observationRequest)
      val expectedAccountFeatures = List(
        simulationStartRequest.initialQuoteFunds.normalize,
        buyOrder.holds.normalize,
        simulationStartRequest.initialProductFunds.normalize,
        sellOrder.holds.normalize,
      )

      assert(expectedAccountFeatures sameElements accountFeatures)
    }
  }
}

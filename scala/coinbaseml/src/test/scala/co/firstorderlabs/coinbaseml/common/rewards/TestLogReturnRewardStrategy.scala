package co.firstorderlabs.coinbaseml.common.rewards

import co.firstorderlabs.coinbaseml.common.utils.TestUtils.doubleEquality
import co.firstorderlabs.coinbaseml.common.utils.Utils.logEpsilon
import co.firstorderlabs.coinbaseml.fakebase.TestData.RequestsData._
import co.firstorderlabs.coinbaseml.fakebase.{Account, Configs, Constants, Exchange}
import co.firstorderlabs.common.protos.fakebase.CancellationRequest
import org.scalatest.funspec.AnyFunSpec

class TestLogReturnRewardStrategy extends AnyFunSpec{
  Configs.testMode = true

  describe("LogReturnRewardStrategy") {
    it("The reward calculated should be the log of the ratio of portfolio values " +
      "between two consecutive time steps") {
      Exchange.start(simulationStartRequest)
      TestReturnRewardStrategy.populateOrderBook
      val portfolioValue1 = ReturnRewardStrategy.calcPortfolioValue(Exchange.getSimulationMetadata.currentTimeInterval)
      Account.placedOrders.keys.foreach(orderId => Account.cancelOrder(new CancellationRequest(orderId)))
      Exchange.step(Constants.emptyStepRequest)
      val portfolioValue2 = ReturnRewardStrategy.calcPortfolioValue(Exchange.getSimulationMetadata.currentTimeInterval)
      val expectedReward = logEpsilon(portfolioValue2 / portfolioValue1)

      assert(expectedReward === LogReturnRewardStrategy.calcReward)
    }
  }

}

package co.firstorderlabs.common.rewards

import co.firstorderlabs.common.utils.Utils.logEpsilon
import co.firstorderlabs.common.utils.TestUtils.doubleEquality
import co.firstorderlabs.fakebase.TestData.RequestsData._
import co.firstorderlabs.fakebase.protos.fakebase.CancellationRequest
import co.firstorderlabs.fakebase.{Account, Configs, Constants, Exchange}
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

package co.firstorderlabs.coinbaseml.common.rewards

import co.firstorderlabs.coinbaseml.common.Configs.testMode
import co.firstorderlabs.coinbaseml.common.utils.TestUtils.{buildStepRequest, doubleEquality}
import co.firstorderlabs.coinbaseml.common.utils.Utils.{getResult, logEpsilon}
import co.firstorderlabs.coinbaseml.fakebase.TestData.RequestsData._
import co.firstorderlabs.coinbaseml.fakebase._
import co.firstorderlabs.common.protos.fakebase.CancellationRequest
import org.scalatest.funspec.AnyFunSpec

class TestLogReturnRewardStrategy extends AnyFunSpec{
  testMode = true

  describe("LogReturnRewardStrategy") {
    it("The reward calculated should be the log of the ratio of portfolio values " +
      "between two consecutive time steps") {
      val simulationId = getResult(Exchange.start(simulationStartRequest)).simulationId.get
      implicit val simulationState = SimulationState.getOrFail(simulationId)
      implicit val matchingEngineState = simulationState.matchingEngineState
      val accountState = simulationState.accountState
      TestReturnRewardStrategy.populateOrderBook(simulationId)
      val portfolioValue1 = ReturnRewardStrategy.currentPortfolioValue
      accountState.placedOrders.keys.foreach(orderId => Account.cancelOrder(new CancellationRequest(orderId, Some(simulationId))))
      Exchange.step(buildStepRequest(simulationId))
      val portfolioValue2 = ReturnRewardStrategy.currentPortfolioValue
      val expectedReward = logEpsilon(portfolioValue2 / portfolioValue1)

      assert(expectedReward === LogReturnRewardStrategy.calcReward)
    }
  }

}

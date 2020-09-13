package co.firstorderlabs.common.featurizers

import co.firstorderlabs.common
import co.firstorderlabs.common.InfoAggregator
import co.firstorderlabs.common.protos.FeaturizerServiceGrpc.FeaturizerService
import co.firstorderlabs.common.protos.{
  Features,
  Observation,
  ObservationRequest,
  Reward,
  RewardRequest,
  RewardStrategy
}
import co.firstorderlabs.common.rewards.{
  LogReturnRewardStrategy,
  ReturnRewardStrategy
}
import co.firstorderlabs.common.types.Exceptions.UnrecognizedRewardStrategy
import co.firstorderlabs.common.utils.Utils.getResult
import com.google.protobuf.empty.Empty

import scala.concurrent.Future

object Featurizer extends FeaturizerService {
  def getObservation(
      observationRequest: ObservationRequest
  ): Future[Observation] = {
    val reward = observationRequest.rewardRequest match {
      case Some(rewardRequest) => Some(getResult(getReward(rewardRequest)))
      case None                => None
    }

    val observation = Observation(Some(construct(observationRequest)), reward, Some(InfoAggregator.getInfoDict))

    Future.successful(observation)
  }

  def construct(observationRequest: ObservationRequest): Features =
    Features(
      AccountFeaturizer.construct(observationRequest),
      OrderBookFeaturizer.construct(observationRequest),
      TimeSeriesFeaturizer.construct(observationRequest)
    )

  def getReward(rewardRequest: RewardRequest): Future[Reward] = {
    val rewardStrategy = rewardRequest.rewardStrategy match {
      case RewardStrategy.ReturnRewardStrategy    => ReturnRewardStrategy
      case RewardStrategy.LogReturnRewardStrategy => LogReturnRewardStrategy
      case _                                      => throw new UnrecognizedRewardStrategy
    }

    val reward = Reward(rewardStrategy.calcReward)

    Future.successful(reward)
  }

  override def getInfoDict(request: Empty): Future[common.InfoDict] =
    Future.successful(InfoAggregator.getInfoDict)
}

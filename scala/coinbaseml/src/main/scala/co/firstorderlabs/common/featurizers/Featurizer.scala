package co.firstorderlabs.common.featurizers

import java.util.logging.Logger

import co.firstorderlabs.common
import co.firstorderlabs.common.InfoAggregator
import co.firstorderlabs.common.protos.FeaturizerServiceGrpc.FeaturizerService
import co.firstorderlabs.common.protos.{Features, Observation, ObservationRequest, Reward, RewardRequest, RewardStrategy}
import co.firstorderlabs.common.rewards.{LogReturnRewardStrategy, ReturnRewardStrategy}
import co.firstorderlabs.common.types.Exceptions.UnrecognizedRewardStrategy
import co.firstorderlabs.common.utils.Utils.getResult
import co.firstorderlabs.fakebase.{Snapshot, Snapshotable}
import com.google.protobuf.empty.Empty

import scala.concurrent.Future

case class FeaturizerSnapshot(
    orderBookFeaturizerSnapshot: OrderBookFeaturizerSnapshot,
    timeSeriesFeaturizerSnapshot: TimeSeriesFeaturizerSnapshot
) extends Snapshot

object Featurizer
    extends FeaturizerService
    with Snapshotable[FeaturizerSnapshot] {
  private val logger = Logger.getLogger(Featurizer.toString)

  def getObservation(
      observationRequest: ObservationRequest
  ): Future[Observation] = {
    val startTime = System.currentTimeMillis
    val reward = observationRequest.rewardRequest match {
      case Some(rewardRequest) => Some(getResult(getReward(rewardRequest)))
      case None                => None
    }

    //Features are too large to send via grpc. Instead write to socket files using Arrow.
    construct(observationRequest).writeToSockets
    val observation =
      Observation(reward = reward, infoDict = Some(InfoAggregator.getInfoDict))
    val endTime = System.currentTimeMillis
    logger.info(s"Featurizer.getObservation took ${endTime - startTime} ms")

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

  def start(snapshotBufferSize: Int): Unit = {
    OrderBookFeaturizer.start(snapshotBufferSize)
    TimeSeriesFeaturizer.start(snapshotBufferSize)
  }

  def step: Unit = {
    OrderBookFeaturizer.step
    TimeSeriesFeaturizer.step
  }

  override def createSnapshot: FeaturizerSnapshot =
    FeaturizerSnapshot(
      OrderBookFeaturizer.createSnapshot,
      TimeSeriesFeaturizer.createSnapshot
    )

  override def clear: Unit = {
    OrderBookFeaturizer.clear
    TimeSeriesFeaturizer.clear
  }

  override def isCleared: Boolean =
    OrderBookFeaturizer.isCleared && TimeSeriesFeaturizer.isCleared

  override def restore(snapshot: FeaturizerSnapshot): Unit = {
    OrderBookFeaturizer.restore(snapshot.orderBookFeaturizerSnapshot)
    TimeSeriesFeaturizer.restore(snapshot.timeSeriesFeaturizerSnapshot)
  }
}

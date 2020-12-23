package co.firstorderlabs.coinbaseml.common

import java.util.logging.Logger

import co.firstorderlabs.coinbaseml.common.actions.actionizers.Actions.{BuyMarketOrderTransaction, LimitOrderTransaction, SellMarketOrderTransaction}
import co.firstorderlabs.coinbaseml.common.actions.actionizers.{PositionSize, SignalPositionSize}
import co.firstorderlabs.coinbaseml.common.featurizers._
import co.firstorderlabs.coinbaseml.common.rewards.{LogReturnRewardStrategy, ReturnRewardStrategy}
import co.firstorderlabs.coinbaseml.common.types.Exceptions.{UnrecognizedActionizer, UnrecognizedRewardStrategy}
import co.firstorderlabs.coinbaseml.common.utils.ArrowUtils.ArrowFeatureUtils
import co.firstorderlabs.coinbaseml.common.utils.Utils.getResult
import co.firstorderlabs.coinbaseml.fakebase.utils.OrderUtils
import co.firstorderlabs.coinbaseml.fakebase.{Snapshot, Snapshotable}
import co.firstorderlabs.common.protos.environment.EnvironmentServiceGrpc.EnvironmentService
import co.firstorderlabs.common.protos.environment.{ActionRequest, Actionizer, Features, InfoDict, InfoDictKey, Observation, ObservationRequest, Reward, RewardRequest, RewardStrategy}
import co.firstorderlabs.common.protos.events.{Order, OrderMessage}
import com.google.protobuf.empty.Empty

import scala.concurrent.Future

final case class FeaturizerSnapshot(
    orderBookFeaturizerSnapshot: OrderBookFeaturizerSnapshot,
    timeSeriesFeaturizerSnapshot: TimeSeriesFeaturizerSnapshot
) extends Snapshot

object Environment
    extends EnvironmentService
    with Snapshotable[FeaturizerSnapshot] {
  private val logger = Logger.getLogger(Environment.toString)

  override def executeAction(request: ActionRequest): Future[Order] = {
    val action = request.actionizer match {
      case Actionizer.SignalPositionSize => SignalPositionSize.construct(request.actorOutput)
      case Actionizer.PositionSize => PositionSize.construct(request.actorOutput)
      case _ => throw new UnrecognizedActionizer
    }

    action match {
      case action: LimitOrderTransaction if action.side.isbuy => InfoAggregator.increment(InfoDictKey.buyOrdersPlaced)
      case action: LimitOrderTransaction if action.side.issell => InfoAggregator.increment(InfoDictKey.sellOrdersPlaced)
      case _: BuyMarketOrderTransaction => InfoAggregator.increment(InfoDictKey.buyOrdersPlaced)
      case _: SellMarketOrderTransaction => InfoAggregator.increment(InfoDictKey.sellOrdersPlaced)
      case _ =>
    }

    val order = action.execute match {
      case Some(orderEvent) => {
        OrderUtils.orderEventToSealedOneOf(orderEvent)
      }
      case None => new OrderMessage().toOrder
    }

    Future.successful(order)
  }

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
    logger.fine(s"Featurizer.getObservation took ${endTime - startTime} ms")

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

  override def getInfoDict(request: Empty): Future[InfoDict] =
    Future.successful(InfoAggregator.getInfoDict)

  def preStep(actionRequest: Option[ActionRequest]): Unit = {
    actionRequest match {
      case Some(actionRequest) => Environment.executeAction(actionRequest)
      case None =>
    }
  }

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

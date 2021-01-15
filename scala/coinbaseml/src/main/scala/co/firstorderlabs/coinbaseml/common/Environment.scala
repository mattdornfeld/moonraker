package co.firstorderlabs.coinbaseml.common

import java.util.logging.Logger

import co.firstorderlabs.coinbaseml.common.actions.actionizers.Actions.{BuyMarketOrderTransaction, LimitOrderTransaction, SellMarketOrderTransaction}
import co.firstorderlabs.coinbaseml.common.actions.actionizers.{EntrySignal, PositionSize, SignalPositionSize}
import co.firstorderlabs.coinbaseml.common.featurizers._
import co.firstorderlabs.coinbaseml.common.rewards.{LogReturnRewardStrategy, ReturnRewardStrategy}
import co.firstorderlabs.coinbaseml.common.types.Exceptions.{UnrecognizedActionizer, UnrecognizedRewardStrategy}
import co.firstorderlabs.coinbaseml.common.utils.ArrowUtils.ArrowFeatureUtils
import co.firstorderlabs.coinbaseml.common.utils.Utils.getResult
import co.firstorderlabs.coinbaseml.fakebase.utils.OrderUtils
import co.firstorderlabs.coinbaseml.fakebase.{SimulationMetadata, SimulationState, State, StateCompanion}
import co.firstorderlabs.common.protos.environment.EnvironmentServiceGrpc.EnvironmentService
import co.firstorderlabs.common.protos.environment.{ActionRequest, Actionizer, Features, InfoDict, InfoDictKey, Observation, ObservationRequest, Reward, RewardRequest, RewardStrategy}
import co.firstorderlabs.common.protos.events.{Order, OrderMessage}
import co.firstorderlabs.common.types.Types.SimulationId

import scala.concurrent.Future

final case class EnvironmentState(
    infoAggregatorState: InfoAggregatorState,
    orderBookFeaturizerState: OrderBookFeaturizerState,
    timeSeriesFeaturizerState: TimeSeriesFeaturizerState
) extends State[EnvironmentState] {
  override val companion = EnvironmentState

  override def createSnapshot(implicit
      simulationMetadata: SimulationMetadata
  ): EnvironmentState =
    EnvironmentState(
      infoAggregatorState.createSnapshot,
      orderBookFeaturizerState.createSnapshot,
      timeSeriesFeaturizerState.createSnapshot
    )

}

object EnvironmentState extends StateCompanion[EnvironmentState] {
  override def create(implicit
      simulationMetadata: SimulationMetadata
  ): EnvironmentState =
    EnvironmentState(
      InfoAggregatorState.create,
      OrderBookFeaturizerState.create,
      TimeSeriesFeaturizerState.create
    )

  override def fromSnapshot(snapshot: EnvironmentState): EnvironmentState =
    EnvironmentState(
      InfoAggregatorState.fromSnapshot(snapshot.infoAggregatorState),
      OrderBookFeaturizerState.fromSnapshot(snapshot.orderBookFeaturizerState),
      TimeSeriesFeaturizerState.fromSnapshot(snapshot.timeSeriesFeaturizerState)
    )
}

object Environment extends EnvironmentService {
  private val logger = Logger.getLogger(Environment.toString)

  override def executeAction(actionRequest: ActionRequest): Future[Order] = {
    implicit val simulationState =
      SimulationState.getOrFail(actionRequest.simulationId.get)
    implicit val infoAggregatorState =
      simulationState.environmentState.infoAggregatorState
    val action = (actionRequest.actionizer match {
      case Actionizer.SignalPositionSize => SignalPositionSize
      case Actionizer.PositionSize       => PositionSize
      case Actionizer.EntrySignal        => EntrySignal
      case _                             => throw new UnrecognizedActionizer
    }).construct(actionRequest.actorOutput)

    action match {
      case action: LimitOrderTransaction if action.side.isbuy =>
        InfoAggregator.increment(InfoDictKey.buyOrdersPlaced)
      case action: LimitOrderTransaction if action.side.issell =>
        InfoAggregator.increment(InfoDictKey.sellOrdersPlaced)
      case _: BuyMarketOrderTransaction =>
        InfoAggregator.increment(InfoDictKey.buyOrdersPlaced)
      case _: SellMarketOrderTransaction =>
        InfoAggregator.increment(InfoDictKey.sellOrdersPlaced)
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
    implicit val simulationState =
      SimulationState.getOrFail(observationRequest.simulationId.get)
    implicit val simulationMetadata = simulationState.simulationMetadata
    val startTime = System.currentTimeMillis
    val reward = observationRequest.rewardRequest match {
      case Some(rewardRequest) => Some(getResult(getReward(rewardRequest.update(_.simulationId := simulationMetadata.simulationId))))
      case None                => None
    }

    //Features are too large to send via grpc. Instead write to socket files using Arrow.
    construct(observationRequest).writeToSockets
    val observation =
      Observation(
        reward = reward,
        infoDict =
          Some(simulationState.environmentState.infoAggregatorState.infoDict)
      )
    val endTime = System.currentTimeMillis
    logger.fine(s"Featurizer.getObservation took ${endTime - startTime} ms")

    Future.successful(observation)
  }

  def construct(
      observationRequest: ObservationRequest
  )(implicit simulationState: SimulationState): Features =
    Features(
      AccountFeaturizer.construct(observationRequest),
      OrderBookFeaturizer.construct(observationRequest),
      TimeSeriesFeaturizer.construct(observationRequest)
    )

  def getReward(rewardRequest: RewardRequest): Future[Reward] = {
    implicit val simulationState =
      SimulationState.getOrFail(rewardRequest.simulationId.get)
    val rewardStrategy = rewardRequest.rewardStrategy match {
      case RewardStrategy.ReturnRewardStrategy    => ReturnRewardStrategy
      case RewardStrategy.LogReturnRewardStrategy => LogReturnRewardStrategy
      case _                                      => throw new UnrecognizedRewardStrategy
    }

    val reward = Reward(rewardStrategy.calcReward)

    Future.successful(reward)
  }

  override def getInfoDict(simulationId: SimulationId): Future[InfoDict] = {
    val simulationState = SimulationState.getOrFail(simulationId)
    Future.successful(simulationState.environmentState.infoAggregatorState.infoDict)
  }

  def preStep(actionRequest: Option[ActionRequest]): Unit = {
    actionRequest match {
      case Some(actionRequest) => Environment.executeAction(actionRequest)
      case None                =>
    }
  }

  def step(implicit simulationState: SimulationState): Unit = {
    OrderBookFeaturizer.step
    TimeSeriesFeaturizer.step
  }
}

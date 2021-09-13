package co.firstorderlabs.coinbaseml.common

import co.firstorderlabs.coinbaseml.common.Configs.logLevel
import co.firstorderlabs.coinbaseml.common.actions.actionizers.Actionizer
import co.firstorderlabs.coinbaseml.common.actions.actionizers.Actions.{
  BuyMarketOrderTransaction,
  LimitOrderTransaction,
  NoTransaction,
  SellMarketOrderTransaction
}
import co.firstorderlabs.coinbaseml.common.featurizers._
import co.firstorderlabs.coinbaseml.common.rewards.{
  LogReturnRewardStrategy,
  ReturnRewardStrategy
}
import co.firstorderlabs.coinbaseml.common.types.Exceptions.UnrecognizedRewardStrategy
import co.firstorderlabs.coinbaseml.common.utils.ArrowUtils.ArrowFeatures
import co.firstorderlabs.coinbaseml.common.utils.Utils.getResult
import co.firstorderlabs.coinbaseml.fakebase.utils.OrderUtils
import co.firstorderlabs.coinbaseml.fakebase.{
  SimulationMetadata,
  SimulationState,
  State,
  StateCompanion
}
import co.firstorderlabs.common.protos.environment.EnvironmentServiceGrpc.EnvironmentService
import co.firstorderlabs.common.protos.environment.{
  ActionRequest,
  Info,
  InfoDictKey,
  Observation,
  ObservationRequest,
  Reward,
  RewardRequest,
  RewardStrategy
}
import co.firstorderlabs.common.protos.events.{Order, OrderMessage}
import co.firstorderlabs.common.types.Actionizers.ActionizerState
import co.firstorderlabs.common.types.Types.{Features, SimulationId}

import java.util.logging.Logger
import scala.concurrent.Future

final case class EnvironmentState(
    actionizerState: ActionizerState,
    infoAggregatorState: InfoAggregatorState,
    featurizerState: FeaturizerStateBase
) extends State[EnvironmentState] {
  override val companion = EnvironmentState

  override def createSnapshot(implicit
      simulationState: SimulationState
  ): EnvironmentState =
    EnvironmentState(
      actionizerState.createSnapshot,
      infoAggregatorState.createSnapshot,
      featurizerState.createSnapshot
    )

}

object EnvironmentState extends StateCompanion[EnvironmentState] {
  override def create(implicit
      simulationMetadata: SimulationMetadata
  ): EnvironmentState =
    EnvironmentState(
      simulationMetadata.actionizer.actionizerState.create,
      InfoAggregatorState.create,
      FeaturizerStateBase.create
    )

  override def fromSnapshot(snapshot: EnvironmentState): EnvironmentState = {
    EnvironmentState(
      snapshot.actionizerState.companion.fromSnapshot(snapshot.actionizerState),
      InfoAggregatorState.fromSnapshot(snapshot.infoAggregatorState),
      FeaturizerStateBase.fromSnapshot(snapshot.featurizerState)
    )
  }
}

object Environment extends EnvironmentService {
  private val logger = Logger.getLogger(Environment.toString)
  logger.setLevel(logLevel)

  override def executeAction(actionRequest: ActionRequest): Future[Order] = {
    val simulationState =
      SimulationState.getOrFail(actionRequest.simulationId.get)
    implicit val infoAggregatorState =
      simulationState.environmentState.infoAggregatorState
    val actionizer = Actionizer.fromProto(actionRequest.actionizer)
    val action = if (actionRequest.updateOnly) {
      actionizer.update(actionRequest.actorOutput)(simulationState)
      new NoTransaction
    } else {
      actionizer.updateAndConstruct(actionRequest.actorOutput)(simulationState)
    }

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
      case Some(rewardRequest) =>
        Some(
          getResult(
            getReward(
              rewardRequest
                .update(_.simulationId := simulationMetadata.simulationId)
            )
          )
        )
      case None => None
    }

    //Features are too large to send via grpc. Instead write to socket files using Arrow.
    construct(observationRequest).writeToSocket
    val info = getResult(getInfo(simulationMetadata.simulationId))
    val observation =
      Observation(
        reward = reward,
        info = Some(info)
      )
    val endTime = System.currentTimeMillis
    logger.fine(s"Featurizer.getObservation took ${endTime - startTime} ms")

    Future.successful(observation)
  }

  def construct(
      observationRequest: ObservationRequest
  )(implicit simulationState: SimulationState): Features =
    FeaturizerBase
      .getFeaturizer(observationRequest.featurizer)
      .construct(observationRequest)

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

  override def getInfo(simulationId: SimulationId): Future[Info] = {
    val simulationState = SimulationState.getOrFail(simulationId)
    Future.successful(
      Info(
        actionizerState =
          simulationState.environmentState.actionizerState.toSealedOneOf,
        infoDict =
          Some(simulationState.environmentState.infoAggregatorState.infoDict)
      )
    )
  }

  def preStep(actionRequest: Option[ActionRequest]): Unit = {
    actionRequest match {
      case Some(actionRequest) => Environment.executeAction(actionRequest)
      case None                =>
    }
  }

  def step(
      observationRequest: ObservationRequest
  )(implicit simulationState: SimulationState): Unit =
    FeaturizerBase.getFeaturizer(observationRequest.featurizer).step
}

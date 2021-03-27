package co.firstorderlabs.common.types

import co.firstorderlabs.common.protos.actionizers.{ActionizerConfigsMessage, ActionizerStateMessage, BollingerOnBookVolumeConfigs, BollingerOnBookVolumeState, EmaCrossOverConfigs, EmaCrossOverState, EntrySignalConfigs, EntrySignalState, NoOpActionizerConfigs, NoOpActionizerState, PositionSizeConfigs, PositionSizeState, SignalPositionSizeConfigs, SignalPositionSizeState, ActionizerConfigs => ActionizerConfigsSealedOneof, ActionizerState => ActionizerStateSealedOneof}
import co.firstorderlabs.common.protos.indicators._
import co.firstorderlabs.common.types.Utils.OptionUtils

object Actionizers {
  trait ActionizerConfigs {
    def toSealedOneOf: ActionizerConfigsSealedOneof = {
      val message = ActionizerConfigsMessage()
      this match {
        case configs: SignalPositionSizeConfigs => message.withSignalPositionSize(configs)
        case configs: PositionSizeConfigs => message.withPositionSize(configs)
        case configs: EntrySignalConfigs => message.withEntrySignal(configs)
        case configs: EmaCrossOverConfigs => message.withEmaCrossOver(configs)
        case configs: NoOpActionizerConfigs => message.withNoOpActionizer(configs)
        case configs: BollingerOnBookVolumeConfigs => message.withBollingerOnBookVolume(configs)
        case _ => throw new IllegalArgumentException(s"${this} is an unrecognized actionizer")
      }
      message.toActionizerConfigs
    }
  }

  object ActionizerConfigs {
    def fromSealedOneOf(
        actionizerConfigs: ActionizerConfigsSealedOneof
    ): ActionizerConfigs = {
      val sealedValue = actionizerConfigs.asMessage.sealedValue
      List(
        sealedValue.signalPositionSize,
        sealedValue.positionSize,
        sealedValue.entrySignal,
        sealedValue.emaCrossOver,
        sealedValue.noOpActionizer,
        sealedValue.bollingerOnBookVolume
      ).flatten.headOption.getOrElse(new NoOpActionizerConfigs)
    }
  }

  trait ActionizerState {
    val signal: Double

    def companion: ActionizerStateCompanion

    def createSnapshot: ActionizerState

    def toSealedOneOf: ActionizerStateSealedOneof = {
      val actionizerStateMessage = this match {
        case actionizerState: SignalPositionSizeState =>
          ActionizerStateMessage().withSignalPositionSize(actionizerState)
        case actionizerState: PositionSizeState =>
          ActionizerStateMessage().withPositionSize(actionizerState)
        case actionizerState: EntrySignalState =>
          ActionizerStateMessage().withEntrySignal(actionizerState)
        case actionizerState: EmaCrossOverState =>
          ActionizerStateMessage().withEmaCrossOver(actionizerState)
        case actionizerState: NoOpActionizerState =>
          ActionizerStateMessage().withNoOpActionizer(actionizerState)
        case actionizerState: BollingerOnBookVolumeState =>
          ActionizerStateMessage().withBollingerOnBookVolume(actionizerState)
      }
      actionizerStateMessage.toActionizerState
    }
  }

  trait ActionizerStateCompanion {
    def fromSnapshot(snapshot: ActionizerState): ActionizerState
    def create: ActionizerState
  }

  trait SignalPositionSizeStateBase extends ActionizerState {
    override def createSnapshot: SignalPositionSizeState =
      this match {
        case snapshot: SignalPositionSizeState => snapshot
      }
  }

  trait SignalPositionSizeStateCompanion extends ActionizerStateCompanion {
    override def fromSnapshot(
        snapshot: ActionizerState
    ): SignalPositionSizeState =
      snapshot match {
        case snapshot: SignalPositionSizeState => snapshot
      }

    override def create: SignalPositionSizeState = new SignalPositionSizeState
  }

  trait PositionSizeStateBase extends ActionizerState {
    override def createSnapshot: PositionSizeState =
      this match {
        case snapshot: PositionSizeState => snapshot
      }
  }

  trait PositionSizeStateCompanion extends ActionizerStateCompanion {
    override def fromSnapshot(snapshot: ActionizerState): PositionSizeState =
      snapshot match {
        case snapshot: PositionSizeState => snapshot
      }
    override def create: PositionSizeState = new PositionSizeState
  }

  trait EntrySignalStateBase extends ActionizerState {
    override def createSnapshot: EntrySignalState =
      this match {
        case snapshot: EntrySignalState => snapshot
      }
  }

  trait EntrySignalStateCompanion extends ActionizerStateCompanion {
    override def fromSnapshot(snapshot: ActionizerState): EntrySignalState =
      snapshot match {
        case snapshot: EntrySignalState => snapshot
      }

    override def create: EntrySignalState = new EntrySignalState
  }

  trait EmaCrossOverStateBase extends ActionizerState {
    override def createSnapshot: EmaCrossOverState =
      EmaCrossOverState.fromSnapshot(this)
  }

  trait EmaCrossOverStateCompanion extends ActionizerStateCompanion {
    override def fromSnapshot(snapshot: ActionizerState): EmaCrossOverState =
      snapshot match {
        case snapshot: EmaCrossOverState => snapshot
      }

    override def create: EmaCrossOverState =
      EmaCrossOverState(
        emaFast = ExponentialMovingAverageState.create,
        emaSlow = ExponentialMovingAverageState.create
      )
  }

  trait NoOpActionizerStateBase extends ActionizerState {
    override def createSnapshot: NoOpActionizerState =
      this match {
        case snapshot: NoOpActionizerState => snapshot
      }
  }

  trait NoOpActionizerStateCompanion extends ActionizerStateCompanion {
    override def fromSnapshot(
        snapshot: ActionizerState
    ): NoOpActionizerState =
      snapshot match {
        case snapshot: NoOpActionizerState => snapshot
      }

    override def create: NoOpActionizerState = new NoOpActionizerState
  }

  trait BollingerOnBookVolumeStateBase extends ActionizerState {
    override def createSnapshot: BollingerOnBookVolumeState =
      BollingerOnBookVolumeState.fromSnapshot(this)
  }

  trait BollingerOnBookVolumeStateCompanion extends ActionizerStateCompanion {
    override def fromSnapshot(
        snapshot: ActionizerState
    ): BollingerOnBookVolumeState = {
      snapshot match {
        case snapshot: BollingerOnBookVolumeState => {
          val priceMovingVariance = snapshot.priceMovingVariance
          val movingAverageState = priceMovingVariance.movingAverageState
          val updatedMovingAverageState = movingAverageState.update(
            _.priceBuffer := movingAverageState.priceBuffer.clone
          )
          val updatedPriceMovingVariance = priceMovingVariance.update(
            _.movingAverageState := updatedMovingAverageState
          )
          snapshot.update(_.priceMovingVariance := updatedPriceMovingVariance)
        }
      }

    }
    override def create: BollingerOnBookVolumeState =
      BollingerOnBookVolumeState(
        smoothedOnBookVolume = KaufmanAdaptiveMovingAverageState.create,
        priceMovingVariance = KaufmanAdaptiveMovingVarianceState.create,
        onBookVolume = OnBookVolumeState.create,
        sampledOnBookVolumeDerivative = SampleValueByBarIncrementState.create
      )
  }
}

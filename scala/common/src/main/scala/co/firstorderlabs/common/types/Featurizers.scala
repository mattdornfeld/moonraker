package co.firstorderlabs.common.types

import co.firstorderlabs.common.protos.featurizers.{
  FeaturizerConfigsMessage,
  NoOpConfigs,
  OrderBookConfigs,
  TimeSeriesOrderBookConfigs,
  FeaturizerConfigs => FeaturizerConfigsSealedOneof
}

object Featurizers {
  trait FeaturizerConfigs {
    def toSealedOneOf: FeaturizerConfigsSealedOneof = {
      val message = this match {
        case configs: TimeSeriesOrderBookConfigs =>
          FeaturizerConfigsMessage().withTimeSeriesOrderBookConfigs(configs)
        case configs: NoOpConfigs =>
          FeaturizerConfigsMessage().withNoOpConfigs(configs)
        case configs: OrderBookConfigs =>
          FeaturizerConfigsMessage().withOrderBookConfigs(configs)
        case _ =>
          throw new IllegalArgumentException(
            s"${this} is an unrecognized featurizer"
          )
      }
      message.toFeaturizerConfigs
    }
  }

  object FeaturizerConfigs {
    def fromSealedOneOf(
        featurizerConfigs: FeaturizerConfigsSealedOneof
    ): FeaturizerConfigs = {
      val sealedValue = featurizerConfigs.asMessage.sealedValue
      List(
        sealedValue.timeSeriesOrderBookConfigs,
        sealedValue.noOpConfigs,
        sealedValue.orderBookConfigs
      ).flatten.headOption.getOrElse(new NoOpConfigs)
    }
  }

  trait OrderBookVectorizerConfigs extends {
    val featureBufferSize: Int
    val orderBookDepth: Int
  }

  trait TimeSeriesVectorizerConfigs {
    val featureBufferSize: Int
  }
}

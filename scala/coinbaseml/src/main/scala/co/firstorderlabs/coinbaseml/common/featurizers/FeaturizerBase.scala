package co.firstorderlabs.coinbaseml.common.featurizers

import co.firstorderlabs.common.protos.environment.ObservationRequest

trait FeaturizerBase {
  def construct(observationRequest: ObservationRequest): List[Double]
}

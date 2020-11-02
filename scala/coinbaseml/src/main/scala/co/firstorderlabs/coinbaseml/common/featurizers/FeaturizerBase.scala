package co.firstorderlabs.coinbaseml.common.featurizers

import co.firstorderlabs.coinbaseml.common.protos.ObservationRequest

trait FeaturizerBase {
  def construct(observationRequest: ObservationRequest): List[Double]
}

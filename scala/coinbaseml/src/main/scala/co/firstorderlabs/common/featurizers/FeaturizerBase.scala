package co.firstorderlabs.common.featurizers

import co.firstorderlabs.common.protos.ObservationRequest

trait FeaturizerBase {
  def construct(observationRequest: ObservationRequest): List[Double]
}

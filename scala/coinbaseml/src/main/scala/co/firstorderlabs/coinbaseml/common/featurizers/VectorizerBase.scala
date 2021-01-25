package co.firstorderlabs.coinbaseml.common.featurizers

import co.firstorderlabs.coinbaseml.fakebase.SimulationState
import co.firstorderlabs.common.protos.environment.ObservationRequest

trait VectorizerBase {
  def construct(observationRequest: ObservationRequest)(implicit
      simulationState: SimulationState
  ): List[Double]

  def step(implicit simulationState: SimulationState): Unit
}

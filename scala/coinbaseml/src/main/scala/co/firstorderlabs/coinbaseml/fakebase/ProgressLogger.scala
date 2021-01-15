package co.firstorderlabs.coinbaseml.fakebase

object StepProgressLogger {
  private def render(
      taskName: String,
      maxSteps: Long,
      currentStep: Long,
      portfolioValue: Option[Double],
      stepDuration: Option[Double] = None,
      dataGetDuration: Option[Double] = None,
      matchingEngineDuration: Option[Double] = None,
      environmentDuration: Option[Double] = None,
      numEvents: Option[Int] = None,
      eventsPerMilliSecond: Option[Double] = None
  ): Unit = {
    println(
      s"${taskName}: ${currentStep} / ${maxSteps} steps (" +
        s"portfolioValue = ${portfolioValue.getOrElse("")}, " +
        s"stepDuration = ${stepDuration.getOrElse("")} ms, " +
        s"dataGetDuration = ${dataGetDuration.getOrElse("")} ms, " +
        s"matchingEngineDuration = ${matchingEngineDuration.getOrElse("")} ms, " +
        s"environmentDuration = ${environmentDuration.getOrElse("")} ms, " +
        s"numEvents = ${numEvents.getOrElse("")}, " +
        s"eventsPerMilliSecond = ${eventsPerMilliSecond.getOrElse("")})"
    )
  }

  def reset(implicit
            matchingEngineState: MatchingEngineState,
            simulationMetadata: SimulationMetadata
  ): Unit = {
    render(
      simulationMetadata.taskName,
      simulationMetadata.numSteps,
      simulationMetadata.currentStep,
      matchingEngineState.currentPortfolioValue
    )
  }

  def step(
      stepDuration: Double,
      dataGetDuration: Double,
      matchingEngineDuration: Double,
      environmentDuration: Double,
      numEvents: Int
  )(implicit
      matchingEngineState: MatchingEngineState,
      simulationMetadata: SimulationMetadata
  ): Unit = {
    val eventsPerMillSecond =
      if (stepDuration > 0) numEvents / stepDuration else 0L
    render(
      simulationMetadata.taskName,
      simulationMetadata.numSteps,
      simulationMetadata.currentStep,
      matchingEngineState.currentPortfolioValue,
      Some(stepDuration),
      Some(dataGetDuration),
      Some(matchingEngineDuration),
      Some(environmentDuration),
      Some(numEvents),
      Some(eventsPerMillSecond)
    )
  }
}

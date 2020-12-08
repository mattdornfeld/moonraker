package co.firstorderlabs.coinbaseml.fakebase

final case class StepProgressLogger(task: String, maxSteps: Long) {
  private def render(
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
      s"${task}: ${currentStep} / ${maxSteps} steps (" +
        s"portfolioValue = ${portfolioValue.getOrElse("")}, " +
        s"stepDuration = ${stepDuration.getOrElse("")} ms, " +
        s"dataGetDuration = ${dataGetDuration.getOrElse("")} ms, " +
        s"matchingEngineDuration = ${matchingEngineDuration.getOrElse("")} ms, " +
        s"environmentDuration = ${environmentDuration.getOrElse("")} ms, " +
        s"numEvents = ${numEvents.getOrElse("")}, " +
        s"eventsPerMilliSecond = ${eventsPerMilliSecond.getOrElse("")})"
    )
  }

  def step(
      currentStep: Long,
      stepDuration: Double,
      dataGetDuration: Double,
      matchingEngineDuration: Double,
      environmentDuration: Double,
      numEvents: Int,
  ): Unit = {
    val eventsPerMillSecond =
      if (stepDuration > 0) numEvents / stepDuration else 0L
    render(
      currentStep,
      MatchingEngine.currentPortfolioValue,
      Some(stepDuration),
      Some(dataGetDuration),
      Some(matchingEngineDuration),
      Some(environmentDuration),
      Some(numEvents),
      Some(eventsPerMillSecond)
    )
  }

  def resetTo(stepNum: Long): Unit = {
    render(stepNum, MatchingEngine.currentPortfolioValue)
  }
}

package co.firstorderlabs.coinbaseml.fakebase

final case class ProgressBar(task: String, maxSteps: Long) {
  private var currentStep = 0L

  private def render(
      stepDuration: Option[Double] = None,
      dataGetDuration: Option[Double] = None,
      matchingEngineDuration: Option[Double] = None,
      environmentDuration: Option[Double] = None,
      snapshotDuration: Option[Double] = None,
      numEvents: Option[Int] = None,
      eventsPerMilliSecond: Option[Double] = None
  ): Unit = {
    println(
      s"${task}: ${currentStep} / ${maxSteps} steps (" +
        s"stepDuration = ${stepDuration.getOrElse("")} ms, " +
        s"dataGetDuration = ${dataGetDuration.getOrElse("")} ms, " +
        s"matchingEngineDuration = ${matchingEngineDuration.getOrElse("")} ms, " +
        s"environmentDuration = ${environmentDuration.getOrElse("")} ms, " +
        s"snapshotDuration = ${snapshotDuration.getOrElse("")} ms, " +
        s"numEvents = ${numEvents.getOrElse("")}, " +
        s"eventsPerMilliSecond = ${eventsPerMilliSecond.getOrElse("")})"
    )
  }

  def step(
      stepDuration: Double,
      dataGetDuration: Double,
      matchingEngineDuration: Double,
      environmentDuration: Double,
      snapshotDuration: Double,
      numEvents: Int
  ): Unit = {
    currentStep += 1
    val eventsPerMillSecond =
      if (stepDuration > 0) numEvents / stepDuration else 0L
    render(
      Some(stepDuration),
      Some(dataGetDuration),
      Some(matchingEngineDuration),
      Some(environmentDuration),
      Some(snapshotDuration),
      Some(numEvents),
      Some(eventsPerMillSecond)
    )
  }

  def resetTo(n: Long): Unit = {
    currentStep = n
    render()
  }
}

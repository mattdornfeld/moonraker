package co.firstorderlabs.coinbaseml.fakebase

case class ProgressBar(task: String, maxSteps: Long) {
  private var currentStep = 0L

  private def render(stepDuration: Option[Long] = None, numEvents: Option[Int] = None, eventsPerMilliSecond: Option[Long] = None): Unit = {
    println(
      s"${task}: ${currentStep} / ${maxSteps} steps (" +
        s"stepDuration = ${stepDuration.getOrElse("")} ms, " +
        s"numEvents = ${numEvents.getOrElse("")}, " +
        s"eventsPerMilliSecond = ${eventsPerMilliSecond.getOrElse("")})"
    )
  }

  def step(stepDuration: Long, numEvents: Int): Unit = {
    currentStep += 1
    val eventsPerMillSecond = if (stepDuration > 0) numEvents / stepDuration else 0L
    render(Some(stepDuration), Some(numEvents), Some(eventsPerMillSecond))
  }

  def resetTo(n: Long): Unit = {
    currentStep = n
    render()
  }
}

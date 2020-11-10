package co.firstorderlabs.coinbaseml.fakebase

case class ProgressBar(task: String, maxSteps: Long) {
  private var currentStep = 0L

  private def render(stepDuration: Option[Long] = None, eventsPerMilliSecond: Option[Long] = None): Unit = {
    println(
      s"${task}: ${currentStep} / ${maxSteps} steps (" +
        s"stepDuration = ${stepDuration.getOrElse("")} ms, " +
        s"eventsPerMilliSecond = ${eventsPerMilliSecond.getOrElse("")})"
    )
  }

  def step(stepDuration: Long, numEvents: Int): Unit = {
    currentStep += 1
    val eventsPerMillSecond = numEvents / stepDuration
    render(Some(stepDuration), Some(eventsPerMillSecond))
  }

  def resetTo(n: Long): Unit = {
    currentStep = n
    render()
  }
}

package co.firstorderlabs.coinbaseml.fakebase

case class ProgressBar(task: String, maxSteps: Long) {
  private var currentStep = 0L

  private def render(stepDuration: Option[Long] = None): Unit = {
    println(
      s"${task}: ${currentStep} / ${maxSteps} steps (stepDuration = ${stepDuration.getOrElse("")} ms)"
    )
  }

  def step(stepDuration: Long): Unit = {
    currentStep += 1
    render(Some(stepDuration))
  }

  def resetTo(n: Long): Unit = {
    currentStep = n
    render()
  }
}

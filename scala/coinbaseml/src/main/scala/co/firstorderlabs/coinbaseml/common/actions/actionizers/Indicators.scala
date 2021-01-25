package co.firstorderlabs.coinbaseml.common.actions.actionizers

final case class RunningExponentialMovingAverage(
    alpha: Double,
    var value: Double,
    var previousValue: Double
) {

  override def clone(): RunningExponentialMovingAverage =
    RunningExponentialMovingAverage(alpha, value, previousValue)

  def crossAbove(that: RunningExponentialMovingAverage): Boolean =
    previousValue <= that.previousValue && value > that.value

  def crossBelow(that: RunningExponentialMovingAverage): Boolean =
    previousValue >= that.previousValue && value < that.value

  def update(sample: Double): Unit = {
    previousValue = value
    value += alpha * (sample - value)
  }
}

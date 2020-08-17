package co.firstorderlabs.common.utils

object Utils {
  def logEpsilon(x: Double, epsilon: Double = 1e-10): Double =
    Math.log(x + epsilon)
}

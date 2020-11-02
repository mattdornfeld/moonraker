package co.firstorderlabs.coinbaseml.common.utils

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

object Utils {
  implicit class DoubleEquality(x: Double) {
    def ===(y: Double, tolerance: Double = 1e-10): Boolean = Math.abs(x - y) < tolerance
  }

  implicit class When[A, B](a: A) {
    def when(condition: Boolean)(f: A => A): A = if (condition) f(a) else a

    def whenElse(condition: Boolean)(f: A => B, g: A => B): B =
      if (condition) f(a) else g(a)
  }

  def logEpsilon(x: Double, epsilon: Double = 1e-10): Double =
    Math.log(x + epsilon)

  /** Immediately returns the value contained in a Future. Will not wait for Future to complete.
   *
   * @param future
   * @tparam A
   * @return
   */
  def getResult[A](future: Future[A]): A = {
    Await.result(future, Duration.MinusInf)
  }

  /** Same as getResult but wraps Future value in Option
   *
   * @param future
   * @tparam A
   * @return
   */
  def getResultOptional[A](future: Future[A]): Option[A] = {
    Some(getResult(future))
  }

  def measureRunTime[R](block: => R, tag: Option[String] = None): R = {
    val t0 = System.currentTimeMillis
    val result = block
    val t1 = System.currentTimeMillis()
    tag match {
      case Some(tag) => println(s"${tag} @ Elapsed time: " + (t1 - t0) + "ms")
      case None => println("Elapsed time: " + (t1 - t0) + "ms")
    }
    result
}
}

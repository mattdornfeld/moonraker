package co.firstorderlabs.fakebase

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.Duration

object Utils {
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
}

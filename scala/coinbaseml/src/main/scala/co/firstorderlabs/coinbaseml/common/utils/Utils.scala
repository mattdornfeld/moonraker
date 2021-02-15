package co.firstorderlabs.coinbaseml.common.utils

import co.firstorderlabs.coinbaseml.common.utils.Utils.Interval.IntervalType
import fs2.{Pure, Stream}

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

object Utils {
  final case class Interval(
      minValue: Double,
      maxValue: Double,
      intervalType: IntervalType.Value = IntervalType.halfOpenLeft
  ) {
    def contains(value: Double): Boolean = {
      val minCompare = value.compareTo(minValue)
      val maxCompare = value.compareTo(maxValue)
      intervalType match {
        case IntervalType.closed => minCompare >= 0 && maxCompare <= 0
        case IntervalType.halfOpenLeft =>
          minCompare > 0 && maxCompare <= 0
        case IntervalType.halfOpenRight =>
          minCompare >= 0 && maxCompare < 0
        case IntervalType.open => minCompare > 0 && maxCompare < 0
      }
    }

    def iterator(step: Double): Iterator[Double] = {
      val leftClosed =
        List(IntervalType.closed, IntervalType.halfOpenRight).contains(
          intervalType
        )

      val rightClosed =
        List(IntervalType.closed, IntervalType.halfOpenLeft).contains(
          intervalType
        )

      val start =
        if (leftClosed) BigDecimal(minValue) else BigDecimal(minValue) + step
      val end =
        if (rightClosed) BigDecimal(maxValue) else BigDecimal(maxValue) - step

      (start to end by step)
        .map(_.toDouble)
        .iterator
    }

  }

  object Interval {
    object IntervalType extends Enumeration {
      val closed = Value("closed")
      val halfOpenLeft = Value("halfOpenLeft")
      val halfOpenRight = Value("halfOpenRight")
      val open = Value("open")
    }
  }

  implicit class DoubleUtils(x: Double) {
    def ===(y: Double, tolerance: Double = 1e-10): Boolean =
      Math.abs(x - y) < tolerance

    def clamp(minValue: Double, maxValue: Double): Double =
      if (x < minValue) {minValue}
      else if (x > maxValue) {maxValue}
      else {x}
  }

  implicit class When[A, B](a: A) {
    def when(condition: Boolean)(f: A => A): A = if (condition) f(a) else a

    def whenElse(condition: Boolean)(f: A => B, g: A => B): B =
      if (condition) f(a) else g(a)
  }

  implicit class ParallelSeq[A](seq: Seq[A]) {
    def toStreams(numThreads: Int): Seq[Stream[Pure, A]] = {
      for (n <- (1 to numThreads).reverse)
        yield Stream.emits(seq.zipWithIndex.collect {
          case (e: A, i: Int) if ((i + n) % numThreads) == 0 => e
        })
    }
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
      case None      => println("Elapsed time: " + (t1 - t0) + "ms")
    }
    result
  }
}

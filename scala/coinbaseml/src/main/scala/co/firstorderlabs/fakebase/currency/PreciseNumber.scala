package co.firstorderlabs.fakebase.currency

import java.math.BigDecimal
import java.math.MathContext

abstract class PreciseNumber[T <: PreciseNumber[T]](mathContext: MathContext, value: Either[BigDecimal, String]) extends Ordered[PreciseNumber[T]]{
  val amount = value match {
    case Left(value) => value.round(mathContext)
    case Right(value) => {
      if (value.length == 0) {
        // ScalaPB needs to be able to successfully pass an empty string to subtypes of this class
        new BigDecimal("0").round(mathContext)
      }
      else {
        new BigDecimal(value).round(mathContext)
      }
    }
  }

  def compare(that: PreciseNumber[T]): Int = this.amount.compareTo(that.amount)

  def >=(that: T): Boolean = this > that || this.equalTo(that)

  def >(that: T): Boolean = this.amount.compareTo(that.amount) == 1

  def <=(that: T): Boolean = this < that || this.equalTo(that)

  def <(that: T): Boolean = this.amount.compareTo(that.amount) == -1

  def equalTo(that: T): Boolean = this.amount.compareTo(that.amount) == 0

  def !=(that: T): Boolean = !this.equalTo(that)

  override def hashCode(): Int = this.amount.hashCode()

  override def toString: String = {
    val className = this.getClass.getSimpleName
    s"<$className $amount>"
  }
}

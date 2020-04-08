package co.firstorderlabs.fakebase.currency

import java.math.BigDecimal
import java.math.MathContext

abstract class PreciseNumber[T <: PreciseNumber[T]](mathContext: MathContext, value: Either[BigDecimal, String]) {
  val amount = value match {
    case Left(value) => value.round(mathContext)
    case Right(value) => new BigDecimal(value).round(mathContext)
  }

  def >=(that: T): Boolean = this > that || this.equal_to(that)

  def >(that: T): Boolean = this.amount.compareTo(that.amount) == 1

  def <=(that: T): Boolean = this < that || this.equal_to(that)

  def <(that: T): Boolean = this.amount.compareTo(that.amount) == -1

  def equal_to(that: T): Boolean = this.amount.compareTo(that.amount) == 0

  def !=(that: T): Boolean = !this.equal_to(that)

  override def hashCode(): Int = this.amount.hashCode()

  override def toString: String = {
    val className = this.getClass.getSimpleName
    s"<$className $amount>"
  }
}

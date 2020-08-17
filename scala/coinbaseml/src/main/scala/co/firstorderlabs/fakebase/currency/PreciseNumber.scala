package co.firstorderlabs.fakebase.currency

import java.math.{BigDecimal, RoundingMode}

abstract class PreciseNumber[T <: PreciseNumber[T]](scale: Int, value: Either[BigDecimal, String]) extends Ordered[PreciseNumber[T]]{
  protected val _scale = scale
  val amount = value match {
    case Left(value) => value.setScale(scale, RoundingMode.HALF_UP)
    case Right(value) => {
      if (value.length == 0) {
        // ScalaPB needs to be able to successfully pass an empty string to subtypes of this
        // We interpret empty string as 0
        new BigDecimal("0").setScale(scale, RoundingMode.HALF_UP)
      }
      else {
        new BigDecimal(value).setScale(scale, RoundingMode.HALF_UP)
      }
    }
  }

  def compare(that: PreciseNumber[T]): Int = this.amount.compareTo(that.amount)

  override def equals(that: Any): Boolean = {
    that match {
      case that: PreciseNumber[T] => this.amount == that.amount
      case _ => false
    }
  }

  def >=(that: T): Boolean = this > that || this.equalTo(that)

  def >(that: T): Boolean = this.amount.compareTo(that.amount) == 1

  def <=(that: T): Boolean = this < that || this.equalTo(that)

  def <(that: T): Boolean = this.amount.compareTo(that.amount) == -1

  def equalTo(that: T): Boolean = this.amount.compareTo(that.amount) == 0

  def !=(that: T): Boolean = !this.equalTo(that)

  def toDouble: Double = amount.doubleValue

  def toPlainString: String = amount.toPlainString

  override def hashCode(): Int = this.amount.hashCode()

  override def toString: String = {
    val className = this.getClass.getSimpleName
    s"<$className ${amount}>"
  }
}

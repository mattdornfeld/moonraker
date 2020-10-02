package co.firstorderlabs.fakebase.currency

import java.math.{BigDecimal, RoundingMode}

import co.firstorderlabs.fakebase.Constants
import co.firstorderlabs.fakebase.protos.fakebase.{Currency, Liquidity}
import scalapb.TypeMapper

/**Contains objects for representing currencies and volumes of those currencies.
  *
  */
object Volume {

  /**Trait inherited by companion objects of subclasses of Volume
    *
    * @tparam T is a subclass of Volume. It will represent the volume
    *           of a particular currency.
    */
  trait VolumeCompanion[T <: Volume[T]] {
    type volume = Volume[T]
    val currency: Currency
    val maxVolume: T
    val minVolume: T
    val normalizationFactor: Double
    val scale: Int
    val zeroVolume: T
  }

  /**Abstract class used to define operations on volumes of currency
    *
    * @param scale is the number of decimal points of the currency
    * @param value is the numerical value of the volume
    * @tparam T is a subclass of Volume. It will represent the volume
    *           of a particular currency.
    */
  abstract class Volume[T <: Volume[T]](scale: Int,
                                        value: Either[BigDecimal, String])
      extends PreciseNumber[T](scale, value) {

    val companion: VolumeCompanion[T]

    /**Creates new Volume from this + that
      *
      * @param that
      * @return
      */
    def +(that: T): T

    /**Creates new Volume from this - that
      *
      * @param that
      * @return
      */
    def -(that: T): T

    /**Creates new Volume from this * that. Used to calculate multiples
      * of an existing Volume.
      *
      * @param that
      * @return
      */
    def *(that: Either[BigDecimal, Double]): T

    /** Creates new Volume from this / that. Used to calculate fractions
      * of an existing Volume.
      *
      * @param that
      * @return
      */
    def /(that: Either[BigDecimal, Double]): T

    /** Returns new Volume object plus fees
      *
      * @param liquidity
      * @return
      */
    def addFees(liquidity: Liquidity): T = {
      val feeFraction = Constants.feeFraction(liquidity)
      this * Left(feeFraction.add(new BigDecimal("1.0")))
    }

    def normalize: Double = this.toDouble / companion.normalizationFactor

    /**Checks if this volume is zero.
      *
      * @return
      */
    def isZero: Boolean = this.amount.compareTo(BigDecimal.ZERO) == 0

    /** Returns new Volume object minus fees
      *
      * @param liquidity
      * @return
      */
    def subtractFees(liquidity: Liquidity): T = {
      val feeFraction = Constants.feeFraction(liquidity)
      this * Left((new BigDecimal("1.0")).subtract(feeFraction))
    }
  }

  /**Used to represent volumes of BTC
    *
    * @param value
    */
  class BtcVolume(value: Either[BigDecimal, String])
      extends Volume[BtcVolume](BtcVolume.scale, value) {

    override val companion = BtcVolume

    /**Creates new BTCVolume from this + that
      *
      * @param that
      * @return
      */
    def +(that: BtcVolume): BtcVolume =
      new BtcVolume(Left(this.amount.add(that.amount)))

    /**Creates new USDVolume from this + that
      *
      * @param that
      * @return
      */
    def -(that: BtcVolume): BtcVolume =
      new BtcVolume(Left(this.amount.subtract(that.amount)))

    /**Creates new BTCVolume from this * that
      *
      * @param that
      * @return
      */
    def *(that: Either[BigDecimal, Double]): BtcVolume = {
      that match {
        case Left(that) => new BtcVolume(Left(this.amount.multiply(that)))
        case Right(that) =>
          new BtcVolume(Left(this.amount.multiply(new BigDecimal(that))))
      }
    }

    /**Creates new BTCVolume from this / that
      *
      * @param that
      * @return
      */
    def /(that: Either[BigDecimal, Double]): BtcVolume = {
      that match {
        case Left(that) => new BtcVolume(Left(this.amount.divide(that, _scale, RoundingMode.HALF_UP)))
        case Right(that) =>
          new BtcVolume(Left(this.amount.divide(new BigDecimal(that), _scale, RoundingMode.HALF_UP)))
      }
    }
  }

  /**Used to represent volumes of USD
    *
    * @param value is the numerical value of the volume
    */
  class UsdVolume(value: Either[BigDecimal, String])
      extends Volume[UsdVolume](UsdVolume.scale, value) {

    override val companion = UsdVolume

    /**Creates new USDVolume from this + that
      *
      * @param that
      * @return
      */
    def +(that: UsdVolume): UsdVolume =
      new UsdVolume(Left(this.amount.add(that.amount)))

    /**Creates new USDVolume from this - that
      *
      * @param that
      * @return
      */
    def -(that: UsdVolume): UsdVolume =
      new UsdVolume(Left(this.amount.subtract(that.amount)))

    /**Creates new USDVolume from this * that
      *
      * @param that
      * @return
      */
    def *(that: Either[BigDecimal, Double]): UsdVolume = {
      that match {
        case Left(that) => new UsdVolume(Left(this.amount.multiply(that)))
        case Right(that) =>
          new UsdVolume(Left(this.amount.multiply(new BigDecimal(that))))
      }
    }

    /**Creates new USDVolume from this / that
      *
      * @param that
      * @return
      */
    def /(that: Either[BigDecimal, Double]): UsdVolume = {
      that match {
        case Left(that) => new UsdVolume(Left(this.amount.divide(that, _scale, RoundingMode.HALF_UP)))
        case Right(that) =>
          new UsdVolume(Left(this.amount.divide(new BigDecimal(that), RoundingMode.HALF_UP)))
      }
    }
  }

  /**Companion object of BTCVolume.
    *
    */
  object BtcVolume extends VolumeCompanion[BtcVolume] {
    implicit val typeMapper = TypeMapper[String, BtcVolume](
      value => new BtcVolume(Right(value))
    )(volume => volume.amount.toString)
    val currency = Currency.BTC
    val maxVolume = new BtcVolume(Right("1e4"))
    val minVolume = new BtcVolume(Right("1e-3"))
    val normalizationFactor = 1e3
    lazy val scale = 8
    val zeroVolume = new BtcVolume(Right("0.0"))
  }

  /**Companion object of USDVolume.
    *
    */
  object UsdVolume extends VolumeCompanion[UsdVolume] {
    implicit val typeMapper = TypeMapper[String, UsdVolume](
      value => new UsdVolume(Right(value))
    )(volume => volume.amount.toString)
    val currency = Currency.USD
    val maxVolume = new UsdVolume(Right("1e10"))
    val minVolume = new UsdVolume(Right("1e-2"))
    val normalizationFactor = 1e6
    lazy val scale = 2
    val zeroVolume = new UsdVolume(Right("0.0"))
  }
}

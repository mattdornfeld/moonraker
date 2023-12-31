package co.firstorderlabs.common.currency

import java.math.{BigDecimal, RoundingMode}

import co.firstorderlabs.common.currency.Volume.{BtcVolume, UsdVolume, Volume, VolumeCompanion}
import co.firstorderlabs.common.types.Types.ProductId
import scalapb.TypeMapper

/**Contains objects for representing exchange products and prices of those products.
  *
  */
object Price {
  /**Trait inherited by companion objects of subclasses of Price
    *
    * @tparam A is a subclass of Volume. It will represent volumes of product currency.
    * @tparam B is a subclass of Volume. It will represent
    */
  trait ProductPriceCompanion[A <: Volume[A], B <: Volume[B]] {
    type ProductVolume <: A
    type QuoteVolume <: B
    val ProductVolume: VolumeCompanion[A]
    val QuoteVolume: VolumeCompanion[B]
    val maxPrice: ProductPrice[A, B]
    val minPrice: ProductPrice[A, B]
    val normalizationFactor: Double
    val productId: ProductId
    val zeroPrice: ProductPrice[A, B]

    def getProductId(): ProductId = ProductId(ProductVolume.currency, QuoteVolume.currency)
  }

  abstract class ProductPrice[A <: Volume[A], B <: Volume[B]](
    scale: Int,
    value: Either[BigDecimal, String]
  ) extends PreciseNumber[ProductPrice[A, B]](scale, value) {
    private val _hashCode = amount.hashCode
    val companion: ProductPriceCompanion[A, B]

    /**Creates new ProductPrice from this + that
      *
      * @param that
      * @return
      */
    def +(that: ProductPrice[A, B]): ProductPrice[A, B]

    /**Creates new ProductPrice from this - that
      *
      * @param that
      * @return
      */
    def -(that: ProductPrice[A, B]): ProductPrice[A, B]

    /**Creates new Volume from this * that, where that is a subclass of Volume.
      * The use case for this is to represent multiply a price (i.e. Volume / Volume)
      * by a Volume to get the Volume amount required to pay for that Volume.
      *
      * @param that
      * @return
      */
    def *(that: A): B

    /**Creates new ProductPrice from this / that. Used to calculate fractions
      * and multiples of an existing ProductPrice. Note the use case for this is a little unusual.
      * Since the * operator returns a subclass of Volume, the division operator is used to
      * calculate both multiples and fractions of a ProductPrice. To calulate the former divide by
      * the reciprocal of the number you want to multiply.
      *
      * @param that
      * @return
      */
    def /(that: Either[BigDecimal, Double]): ProductPrice[A, B]

    override def hashCode(): Int = _hashCode

    def normalize: Double = this.toDouble / companion.normalizationFactor
  }

  @SerialVersionUID(1000L)
  final class BtcUsdPrice(value: Either[BigDecimal, String])
      extends ProductPrice[BtcVolume, UsdVolume](UsdVolume.scale, value) {

    val companion = BtcUsdPrice
    type ProductVolume = BtcVolume
    type QuoteVolume = UsdVolume

    def +(
      that: ProductPrice[BtcVolume, UsdVolume]
    ): BtcUsdPrice =
      new BtcUsdPrice(Left(this.amount.add(that.amount)))

    def -(
      that: ProductPrice[BtcVolume, UsdVolume]
    ): BtcUsdPrice =
      new BtcUsdPrice(Left(this.amount.subtract(that.amount)))

    def *(that: BtcVolume): UsdVolume =
      new UsdVolume(Left(this.amount.multiply(that.amount)))

    def /(that: Either[BigDecimal, Double]): BtcUsdPrice = {
      that match {
        case Left(that) => new BtcUsdPrice(Left(this.amount.divide(that, _scale, RoundingMode.HALF_UP)))
        case Right(that) =>
          new BtcUsdPrice(Left(this.amount.divide(new BigDecimal(that), _scale, RoundingMode.HALF_UP)))
      }
    }
  }

  object BtcUsdPrice extends ProductPriceCompanion[BtcVolume, UsdVolume] {
    type ProductVolume = BtcVolume
    type QuoteVolume = UsdVolume

    implicit val typeMapper = TypeMapper[String, BtcUsdPrice](
      value => new BtcUsdPrice(Right(value))
    )(volume => volume.amount.toString)

    val ProductVolume = BtcVolume
    val QuoteVolume = UsdVolume
    val maxPrice = new BtcUsdPrice(Right("1e10"))
    val minPrice = new BtcUsdPrice(Right("1e-2"))
    val normalizationFactor = 100e3
    val productId = getProductId
    val zeroPrice = new BtcUsdPrice(Right("0.0"))
  }
}

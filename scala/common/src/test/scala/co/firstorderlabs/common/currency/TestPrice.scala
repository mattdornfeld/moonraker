package co.firstorderlabs.common.currency

import co.firstorderlabs.common.currency.Price.BtcUsdPrice
import co.firstorderlabs.common.currency.Volume.{BtcVolume, UsdVolume}
import org.scalatest.funspec.AnyFunSpec

class TestPrice extends AnyFunSpec {
  describe("BtcUsdPrice") {
    it("Should have the following operations") {
      val price = new BtcUsdPrice(Right("1.0"))
      val priceMultiple = price / Right(0.5)
      val productVolume = new BtcVolume(Right("2.0"))
      val quoteVolume = new UsdVolume((Right("2.0")))

      assert((price + price).equalTo(priceMultiple))
      assert((price * productVolume).equalTo(quoteVolume))
      assert((price - price).equalTo(BtcUsdPrice.zeroPrice))

      // These operations only need to be tested once, since they are
      // the same for all subclasses of PreciseNumber.
      assert(priceMultiple > price && priceMultiple >= price)
      assert(price < priceMultiple && price <= priceMultiple)
      assert(price != priceMultiple)
      assert(price.hashCode() == 3102)
    }
  }
}


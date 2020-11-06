package co.firstorderlabs.common.currency

import co.firstorderlabs.common.currency.Volume.{BtcVolume, UsdVolume}
import org.scalatest.funspec.AnyFunSpec

class TestVolume extends AnyFunSpec {
  describe("BTCVolume") {
    it("Should have the following operations") {
      val vol = new BtcVolume(Right("1.0"))

      assert((vol + vol).equalTo(vol * Right(2.0)))
      assert((vol - vol).equalTo(BtcVolume.zeroVolume))
      assert((vol / Right(0.5)).equalTo(vol + vol))
      assert(!vol.isZero)
      assert(BtcVolume.zeroVolume.isZero)
    }
  }

  describe("USDVolume") {
    it("Should have the following operations") {
      val vol = new UsdVolume(Right("1.0"))

      assert((vol + vol).equalTo(vol * Right(2.0)))
      assert((vol - vol).equalTo(UsdVolume.zeroVolume))
      assert((vol / Right(0.5)).equalTo(vol + vol))
      assert(!vol.isZero)
      assert(UsdVolume.zeroVolume.isZero)
    }
  }
}

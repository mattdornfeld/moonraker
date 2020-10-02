package co.firstorderlabs.fakebase.currency

import org.scalatest.funspec.AnyFunSpec
import co.firstorderlabs.fakebase.currency.Volume.{BtcVolume,UsdVolume}

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

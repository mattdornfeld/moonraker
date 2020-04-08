package main

import co.firstorderlabs.fakebase.currency.Configs._
import co.firstorderlabs.fakebase.currency.Volume._

object Main extends App {
  val x = new BtcVolume(Right("1.0"))
  val y = new UsdVolume((Right("1.0")))
  println(ProductPrice.ProductVolume.zeroVolume > x)
}

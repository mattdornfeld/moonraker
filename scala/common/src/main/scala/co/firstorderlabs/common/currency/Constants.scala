package co.firstorderlabs.common.currency

import java.math.BigDecimal

import co.firstorderlabs.common.protos.events.Liquidity

object Constants {
  val feeFraction = Map[Liquidity, BigDecimal](
    Liquidity.maker -> new BigDecimal("0.005"),
    Liquidity.taker -> new BigDecimal("0.005")
  )
}

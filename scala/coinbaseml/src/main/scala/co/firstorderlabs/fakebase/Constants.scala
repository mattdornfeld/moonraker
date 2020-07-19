package co.firstorderlabs.fakebase

import java.math.BigDecimal

import co.firstorderlabs.fakebase.protos.fakebase.{Liquidity, StepRequest}
import com.google.protobuf.empty.Empty

object Constants {
  val feeFraction = Map[Liquidity, BigDecimal](
    Liquidity.maker -> new BigDecimal("0.005"),
    Liquidity.taker -> new BigDecimal("0.005")
  )
  val emptyProto = new Empty
  val emptyStepRequest = new StepRequest
}

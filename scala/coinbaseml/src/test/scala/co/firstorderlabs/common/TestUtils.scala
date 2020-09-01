package co.firstorderlabs.common

import org.scalactic.TolerantNumerics

object TestUtils {
  implicit val doubleEquality = TolerantNumerics.tolerantDoubleEquality(1e-10)
}

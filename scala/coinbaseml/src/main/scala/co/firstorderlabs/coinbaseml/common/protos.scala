package co.firstorderlabs.coinbaseml.common

import co.firstorderlabs.coinbaseml.common.protos.{InfoDict => InfoDictProto}
import co.firstorderlabs.coinbaseml.common.{InfoDict => InfoDictNative}

import scalapb.TypeMapper

package object protos {
  implicit val infoDictTypeMapper =
    TypeMapper[InfoDictProto, InfoDictNative](infoDictProto =>
      InfoDictNative.fromProto(infoDictProto)
    )(infoDictNative => InfoDictNative.toProto(infoDictNative))
}

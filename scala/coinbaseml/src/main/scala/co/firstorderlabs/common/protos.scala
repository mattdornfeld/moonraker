package co.firstorderlabs.common

import co.firstorderlabs.common.protos.{InfoDict => InfoDictProto}
import co.firstorderlabs.common.{InfoDict => InfoDictNative}

import scalapb.TypeMapper

package object protos {
  implicit val infoDictTypeMapper =
    TypeMapper[InfoDictProto, InfoDictNative](infoDictProto =>
      InfoDictNative.fromProto(infoDictProto)
    )(infoDictNative => InfoDictNative.toProto(infoDictNative))
}

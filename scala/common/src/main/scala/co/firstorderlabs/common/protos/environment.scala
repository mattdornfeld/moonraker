package co.firstorderlabs.common.protos

import scalapb.TypeMapper

package object environment {
  implicit val infoDictTypeMapper =
    TypeMapper[InfoDictProto, InfoDict](infoDictProto =>
      InfoDict.fromProto(infoDictProto)
    )(infoDict => InfoDict.toProto(infoDict))
}

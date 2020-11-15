package co.firstorderlabs.common.protos.environment

import scala.collection.mutable

object InfoDict {
  def fromProto(infoDictProto: InfoDictProto): InfoDict = {
    val infoDict = new InfoDict
    infoDictProto.infoDict
      .map { item =>
        val key = InfoDictKey.fromName(item._1) match {
          case Some(key) => key
          case None =>
            throw new IllegalArgumentException(
              s"Key ${item._1} is not a member of InfoDictKeys"
            )
        }
        (key, item._2)
      }
      .foreach(item => infoDict.put(item._1, item._2))

    infoDict
  }

  def toProto(infoDict: InfoDict): InfoDictProto = {
    InfoDictProto(infoDict.map(item => (item._1.name, item._2)).toMap)
  }
}

final class InfoDict extends mutable.HashMap[InfoDictKey, Double] {
  def increment(key: InfoDictKey, incrementValue: Double): Unit = {
    val currentValue = getOrElse(key, 0.0)
    update(key, currentValue + incrementValue)
  }

  def instantiate: Unit =
    InfoDictKey.values.foreach { key =>
      put(key, 0.0)
    }
}

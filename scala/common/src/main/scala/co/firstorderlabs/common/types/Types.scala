package co.firstorderlabs.common.types

import java.nio.ByteBuffer
import java.time.{Duration, Instant}

import co.firstorderlabs.common.protos.environment.ObservationRequest
import co.firstorderlabs.common.protos.events.{SimulationId => SimulationIdProto}
import co.firstorderlabs.common.protos.fakebase.{Currency, StepRequest}
import scalapb.TypeMapper

object Types {
  final case class OrderId(orderId: String) extends AnyVal
  final case class OrderRequestId(orderRequestId: String) extends AnyVal
  final case class SimulationId(simulationId: String) extends AnyVal {
    def toObservationRequest: ObservationRequest =
      ObservationRequest(simulationId = Some(this))

    def toStepRequest: StepRequest =
      StepRequest(simulationId = Some(this))
  }

  final case class TradeId(tradeId: Long) extends AnyVal

  final case class ProductId(
      productCurrency: Currency,
      quoteCurrency: Currency
  ) {
    override def toString: String =
      productCurrency.toString + "-" + quoteCurrency.toString
  }

  final case class TimeInterval(startTime: Instant, endTime: Instant) {
    def +(duration: Duration): TimeInterval = {
      val startDt = this.startTime.plus(duration)
      val endDt = this.endTime.plus(duration)
      TimeInterval(startDt, endDt)
    }

    def -(duration: Duration): TimeInterval = {
      val startDt = this.startTime.minus(duration)
      val endDt = this.endTime.minus(duration)
      TimeInterval(startDt, endDt)
    }

    def chunkBy(timeDelta: Duration): List[TimeInterval] = {
      if (size.compareTo(timeDelta) <= 0) {
        List(this)
      } else {
        val numChunks =
          Duration.between(startTime, endTime).dividedBy(timeDelta).toInt
        (for (offset <- 0 to numChunks - 1)
          yield getTimeIntervalOffsetFromStart(offset, timeDelta)).toList
      }
    }

    def contains(instant: Instant): Boolean =
      (instant.compareTo(startTime) >= 0
        && instant.compareTo(endTime) < 0)

    def getSubInterval(instant: Instant, timeDelta: Duration): TimeInterval = {
      val offset =
        Duration.between(startTime, instant).dividedBy(timeDelta).toInt
      getTimeIntervalOffsetFromStart(offset, timeDelta)
    }

    def numStepsTo(timeInterval: TimeInterval, timeDelta: Duration): Int =
      Duration
        .between(startTime, timeInterval.startTime)
        .dividedBy(timeDelta)
        .toInt

    def serialize: Array[Byte] = {
      val byteBuffer = java.nio.ByteBuffer.allocate(4 * 8)
      byteBuffer.putLong(startTime.getEpochSecond)
      byteBuffer.putLong(startTime.getNano)
      byteBuffer.putLong(endTime.getEpochSecond)
      byteBuffer.putLong(endTime.getNano)
      byteBuffer.array
    }

    private def getTimeIntervalOffsetFromStart(
        offset: Int,
        timeDelta: Duration
    ): TimeInterval =
      TimeInterval(
        startTime.plus(timeDelta.multipliedBy(offset)),
        startTime.plus(timeDelta.multipliedBy(offset + 1))
      )

    def size: Duration = Duration.between(startTime, endTime)
  }

  object TimeInterval {
    def deserialize(bytes: Array[Byte]): TimeInterval = {
      val byteBuffer = ByteBuffer.wrap(bytes)
      val startTime =
        Instant.ofEpochSecond(byteBuffer.getLong, byteBuffer.getLong)
      val endTime =
        Instant.ofEpochSecond(byteBuffer.getLong, byteBuffer.getLong)
      TimeInterval(startTime, endTime)
    }

    def fromStrings(startTime: String, endTime: String): TimeInterval =
      TimeInterval(Instant.parse(startTime), Instant.parse(endTime))
  }

  object OrderId {
    implicit val typeMapper =
      TypeMapper[String, OrderId](value => OrderId(value))(orderId =>
        orderId.orderId
      )
  }

  object OrderRequestId {
    implicit val typeMapper = TypeMapper[String, OrderRequestId](value =>
      OrderRequestId(value)
    )(orderRequestId => orderRequestId.orderRequestId)
  }

  object ProductId {
    implicit val typeMapper = TypeMapper[String, ProductId](value =>
      ProductId.fromString(value)
    )(productId => productId.toString)

    def fromString(productId: String): ProductId = {
      val productCurrency = Currency
        .fromName(
          if (productId.contains("-")) productId.split("-").head
          else ""
        )
        .getOrElse(Currency.QUATLOO)

      val quoteCurrency = Currency
        .fromName(
          if (productId.contains("-")) productId.split("-").tail.head
          else ""
        )
        .getOrElse(Currency.QUATLOO)

      ProductId(productCurrency, quoteCurrency)
    }
  }

  object SimulationId {
    implicit val typeMapper =
      TypeMapper[SimulationIdProto, SimulationId](simulationId =>
        SimulationId(simulationId.simulationId)
      )(simulationId => SimulationIdProto(simulationId.simulationId))
  }

  object TradeId {
    implicit val typeMapper =
      TypeMapper[Long, TradeId](value => TradeId(value))(tradeId =>
        tradeId.tradeId
      )
  }
}

package co.firstorderlabs.coinbaseml.fakebase.types

object Exceptions {
  final class CheckpointNotFound extends IllegalStateException
  final class OrderBookEmpty extends IllegalStateException
  final case class OrderNotFound(message: String) extends IllegalArgumentException(message)
  final case class InvalidOrderStatus(message: String) extends IllegalArgumentException(message)
  final case class InvalidOrderType(message: String) extends IllegalArgumentException(message)
  final class SelfTrade extends IllegalStateException
  final case class SimulationNotStarted(message: String) extends IllegalStateException(message)
  final case class SnapshotBufferNotFull(message: String) extends IllegalStateException(message)
  final class SnapshotNotFound extends IllegalStateException
  final case class TimeoutExceeded(message: String) extends IllegalStateException(message)
}

package co.firstorderlabs.fakebase.types

object Exceptions {
  final class CheckpointNotFound extends IllegalStateException
  final case class OrderNotFound(message: String) extends IllegalArgumentException
  final case class InvalidOrderStatus(message: String) extends IllegalArgumentException
  final case class InvalidOrderType(message: String) extends IllegalArgumentException
  final class SelfTrade extends IllegalStateException
  final case class SimulationNotStarted(message: String) extends IllegalStateException
}

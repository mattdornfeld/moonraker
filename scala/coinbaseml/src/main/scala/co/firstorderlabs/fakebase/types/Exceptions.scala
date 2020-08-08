package co.firstorderlabs.fakebase.types

object Exceptions {
  final case class OrderNotFound(message: String) extends Exception
  final case class InvalidOrderStatus(message: String) extends Exception
  final case class InvalidOrderType(message: String) extends Exception
  final class SelfTrade extends Exception
  final case class SimulationNotStarted(message: String) extends Exception
}

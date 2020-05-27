package co.firstorderlabs.fakebase

import java.util.logging.Logger

import co.firstorderlabs.fakebase.Account.{Account, AccountCheckpoint}

trait Checkpoint

trait Checkpointable[A <: Checkpoint] {
  def checkpoint: A
  def clear: Unit
  def restore(checkpoint: A): Unit
}

case class SimulationCheckpoint(
  accountCheckpoint: AccountCheckpoint,
  databaseWorkersCheckpoint: DatabaseWorkersCheckpoint,
  exchangeCheckpoint: ExchangeCheckpoint,
  matchingEngineCheckpoint: MatchingEngineCheckpoint
) extends Checkpoint

object Checkpointer {
  private val logger = Logger.getLogger(Checkpointer.toString)

  def clear: Unit = {
    Account.clear
    DatabaseWorkers.clear
    Exchange.clear
    MatchingEngine.clear
  }

  def createCheckpoint: SimulationCheckpoint = {
    logger.info(s"creating checkpoint at timeInterval ${Exchange.simulationMetadata.get.currentTimeInterval}")
    SimulationCheckpoint(
      Account.checkpoint,
      DatabaseWorkers.checkpoint,
      Exchange.checkpoint,
      MatchingEngine.checkpoint
    )
  }

  def restoreFromCheckpoint(checkpoint: SimulationCheckpoint): Unit = {
    Account.restore(checkpoint.accountCheckpoint)
    DatabaseWorkers.restore(checkpoint.databaseWorkersCheckpoint)
    Exchange.restore(checkpoint.exchangeCheckpoint)
    MatchingEngine.restore(checkpoint.matchingEngineCheckpoint)
  }
}

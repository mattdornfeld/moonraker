package co.firstorderlabs.coinbaseml.fakebase

import co.firstorderlabs.coinbaseml.common.Configs.{logLevel, testMode}

import java.util.logging.Logger
import co.firstorderlabs.common.protos.fakebase.{AccountServiceGrpc, ExchangeServiceGrpc}
import io.grpc.Server
import io.grpc.netty.NettyServerBuilder

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}

object FakebaseServer {
  private val logger = Logger.getLogger(FakebaseServer.toString)
  logger.setLevel(logLevel)
  private val defaultPort = 9090
  private val executionContext = ExecutionContext.global
  var server: Option[Server] = None

  def main(args: Array[String]): Unit = {
    val port = if (args.size > 0) args.head.toInt else defaultPort
    testMode = if (args.size > 1) args(1).toBoolean else false

    start(port) match {
      case Success(_server) => {
        server = Some(_server)
        blockUntilShutdown
      }
      case Failure(_) =>
    }
  }

  private def start(port: Int): Try[Server] = {
    val _server = Try(
      NettyServerBuilder
        .forPort(port)
        .maxInboundMessageSize(100 * 1000 * 1000)
        .maxInboundMetadataSize(100 * 1000 * 1000)
        .addService(AccountServiceGrpc.bindService(Account, executionContext))
        .addService(ExchangeServiceGrpc.bindService(Exchange, executionContext))
        .build
        .start
    )

    _server match {
      case Success(_server) => {
        val testModeEnabled = if (testMode) "enabled" else "disabled"
        FakebaseServer.logger.info(
          s"Fakebase Server started, listening on ${port}"
        )
        FakebaseServer.logger.info(s"Test mode is ${testModeEnabled}")

        sys.addShutdownHook {
          System.err.println(
            "*** shutting down Fakebase server since JVM is shutting down"
          )
          this.shutdown
          System.err.println("*** Fakebase server shut down")
        }
      }
      case Failure(_) => {
        FakebaseServer.logger.info(
          s"Fakebase Server is already listening on ${port}. Exiting process."
        )
      }
    }
    _server
  }

  def shutdown: Unit =
    server match {
      case Some(_server) => {
        _server.shutdown
        server = None
      }
      case None =>
    }

  private def blockUntilShutdown(): Unit =
    server match {
      case Some(_server) => {
        _server.awaitTermination
      }
      case None =>
    }
}

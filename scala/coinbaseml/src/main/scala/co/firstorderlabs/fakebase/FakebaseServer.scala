package co.firstorderlabs.fakebase

import java.util.logging.Logger

import co.firstorderlabs.fakebase.protos.fakebase.{
  AccountServiceGrpc,
  ExchangeServiceGrpc
}
import io.grpc.Server
import io.grpc.netty.NettyServerBuilder

import scala.concurrent.ExecutionContext

object FakebaseServer {
  private val logger = Logger.getLogger(classOf[FakebaseServer].getName)
  private val defaultPort = 9090

  def main(args: Array[String]): Unit = {
    val fakebaseServer = new FakebaseServer(ExecutionContext.global)
    val port = if (args.size > 0) args.head.toInt else defaultPort
    Configs.testMode = if (args.size > 1) args(1).toBoolean else false
    fakebaseServer.start(port)
    fakebaseServer.blockUntilShutdown
  }
}

class FakebaseServer(executionContext: ExecutionContext) { self =>
  private[this] var server: Server = null

  private def start(port: Int): Unit = {
    server = NettyServerBuilder
      .forPort(port)
      .maxInboundMessageSize(100 * 1000 * 1000)
      .maxInboundMetadataSize(100 * 1000 * 1000)
      .addService(AccountServiceGrpc.bindService(Account, executionContext))
      .addService(ExchangeServiceGrpc.bindService(Exchange, executionContext))
      .build
      .start

    val testModeEnabled = if (Configs.testMode) "enabled" else "disabled"
    FakebaseServer.logger.info(s"Fakebase Server started, listening on ${port}")
    FakebaseServer.logger.info(s"Test mode is ${testModeEnabled}")

    sys.addShutdownHook {
      System.err.println(
        "*** shutting down Fakebase server since JVM is shutting down"
      )
      this.stop()
      System.err.println("*** Fakebase server shut down")
    }
  }

  private def stop(): Unit = {
    if (server != null) {
      server.shutdown()
    }
  }

  private def blockUntilShutdown(): Unit = {
    if (server != null) {
      server.awaitTermination()
    }
  }
}

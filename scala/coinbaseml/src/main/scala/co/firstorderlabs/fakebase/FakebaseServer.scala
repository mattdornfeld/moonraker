package co.firstorderlabs.fakebase

import java.util.logging.Logger

import co.firstorderlabs.fakebase.Account.Account
import co.firstorderlabs.fakebase.protos.fakebase.{AccountServiceGrpc, ExchangeServiceGrpc}
import io.grpc.Server
import io.grpc.netty.NettyServerBuilder

import scala.concurrent.ExecutionContext

object FakebaseServer {
  val account = new Account
  val exchange = new Exchange
  private val logger = Logger.getLogger(classOf[FakebaseServer].getName)
  private val port = 9090

  def main(args: Array[String]): Unit = {
    val fakebaseServer = new FakebaseServer(ExecutionContext.global)
    fakebaseServer.start
    fakebaseServer.blockUntilShutdown
  }
}

class FakebaseServer(executionContext: ExecutionContext) {self =>
  private[this] var server: Server = null

  private def start(): Unit = {
    server = NettyServerBuilder
      .forPort(FakebaseServer.port)
      .maxInboundMessageSize(100 * 1000 * 1000)
      .maxInboundMetadataSize(100 * 1000 * 1000)
      .addService(AccountServiceGrpc.bindService(FakebaseServer.account, executionContext))
      .addService(ExchangeServiceGrpc.bindService(FakebaseServer.exchange, executionContext))
      .build
      .start

    FakebaseServer.logger.info("Server started, listening on " + FakebaseServer.port)
    sys.addShutdownHook {
      System.err.println("*** shutting down gRPC server since JVM is shutting down")
      this.stop()
      System.err.println("*** server shut down")
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

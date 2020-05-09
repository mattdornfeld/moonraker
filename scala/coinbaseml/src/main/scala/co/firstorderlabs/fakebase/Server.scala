package co.firstorderlabs.fakebase

import java.util.logging.Logger

import io.grpc.{Server, ServerBuilder}

import scala.concurrent.{ExecutionContext, Future}
import co.firstorderlabs.fakebase.Account.Account

object Server {
  val account = new Account
  val exchange = new Exchange
//  private val logger = Logger.getLogger(classOf[ExchangeServer].getName)
//  private val port = 50051

//  def main(args: Array[String]): Unit = {
//    val server = new ExchangeServer(ExecutionContext.global)
//    server.start()
//    server.blockUntilShutdown()
//  }
}

//class ExchangeServer(executionContext: ExecutionContext) {
//  private[this] var server: Server = null
//
//  private def start(): Unit = {
//    server = ServerBuilder.forPort(ExchangeServer.port).addService(ExchangeServiceGrpc.bindService(new ExchangeService, executionContext)).build.start
//    ExchangeServer.logger.info("Server started, listening on " + ExchangeServer.port)
//    sys.addShutdownHook {
//      System.err.println("*** shutting down gRPC server since JVM is shutting down")
//      this.stop()
//      System.err.println("*** server shut down")
//    }
//  }
//
//  private def stop(): Unit = {
//    if (server != null) {
//      server.shutdown()
//    }
//  }
//
//  private def blockUntilShutdown(): Unit = {
//    if (server != null) {
//      server.awaitTermination()
//    }
//  }
//}
//
//private class ExchangeService extends ExchangeServiceGrpc.ExchangeService {
//  override def step(request: Events): Future[Events] = {
//    Future.successful(Events())
//  }
//}
//

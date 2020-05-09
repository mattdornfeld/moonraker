//package main
//
//import io.grpc.examples.helloworld.helloworld.{HelloRequest, GreeterGrpc}
//import io.grpc.examples.helloworld.helloworld.GreeterGrpc.GreeterBlockingStub
//import io.grpc.{StatusRuntimeException, ManagedChannelBuilder, ManagedChannel}
//
//
//object Main extends App {
//  val x= new BuyMarketOrder(
//    new UsdVolume(Right("1.00")),
//    Some(new OrderId("dsfsd")),
//    Some(new ProductId(Currency.BTC, Currency.USD)),
//    OrderStatus.received,
//    OrderSide.buy,
//    Some(new Timestamp(10))
//  )
//  println(OrderSide.buy.name)
//  println(x)
//}

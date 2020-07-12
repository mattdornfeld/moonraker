package co.firstorderlabs.fakebase.TestData

import java.time.Instant

import co.firstorderlabs.fakebase.currency.Configs.ProductPrice
import co.firstorderlabs.fakebase.currency.Configs.ProductPrice.{ProductVolume, QuoteVolume}
import co.firstorderlabs.fakebase.protos.fakebase._
import co.firstorderlabs.fakebase.types.Types.Datetime
import com.google.protobuf.duration.Duration

object RequestsData {
  val buyLimitOrderRequest = new BuyLimitOrderRequest(
        new ProductPrice(Right("1000.00")),
        ProductPrice.productId,
        new ProductVolume(Right("1.000000")),
        false
      )

  val buyMarketOrderRequest = new BuyMarketOrderRequest(
    new QuoteVolume(Right("1000.00")),
    ProductPrice.productId
  )

  val orderBooksRequest = new OrderBooksRequest(10)

  val sellLimitOrderRequest = new SellLimitOrderRequest(
        new ProductPrice(Right("1001.00")),
        ProductPrice.productId,
        new ProductVolume(Right("1.000000")),
        false
      )

  val sellMarketOrderRequest = new SellMarketOrderRequest(
    ProductPrice.productId,
    new ProductVolume(Right("1.000000")),
  )

  val simulationStartRequest = new SimulationStartRequest(
    Datetime(Instant.parse("2019-11-20T19:20:50.63Z")),
    Datetime(Instant.parse("2019-11-20T19:25:50.63Z")),
    Some(Duration(30)),
    0,
    new ProductVolume(Right("100.000000")),
    new QuoteVolume(Right("10000.00"))
  )

  val checkpointedSimulationStartRequest = new SimulationStartRequest(
    Datetime(Instant.parse("2019-11-20T19:20:50.63Z")),
    Datetime(Instant.parse("2019-11-20T19:25:50.63Z")),
    Some(Duration(30)),
    3,
    new ProductVolume(Right("100.000000")),
    new QuoteVolume(Right("10000.00"))
  )
}

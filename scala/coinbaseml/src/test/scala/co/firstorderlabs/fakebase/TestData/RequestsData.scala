package co.firstorderlabs.fakebase.TestData

import java.time.{Duration, Instant}

import co.firstorderlabs.common.protos.ObservationRequest
import co.firstorderlabs.fakebase.currency.Configs.ProductPrice
import co.firstorderlabs.fakebase.currency.Configs.ProductPrice.{ProductVolume, QuoteVolume}
import co.firstorderlabs.fakebase.protos.fakebase._

object RequestsData {
  val buyLimitOrderRequest = new BuyLimitOrderRequest(
        new ProductPrice(Right("1000.00")),
        ProductPrice.productId,
        new ProductVolume(Right("1.000000")),
        false,
        Some(Duration.ofDays(10)),
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
        false,
        Some(Duration.ofDays(10)),
      )

  val sellMarketOrderRequest = new SellMarketOrderRequest(
    ProductPrice.productId,
    new ProductVolume(Right("1.000000")),
  )

  val simulationStartRequest = new SimulationStartRequest(
    Instant.parse("2019-11-20T19:20:50.63Z"),
    Instant.parse("2019-11-20T19:25:50.63Z"),
    Some(Duration.ofSeconds(30)),
    0,
    new ProductVolume(Right("100.000000")),
    new QuoteVolume(Right("10000.00")),
    snapshotBufferSize = 3,
  )

  val simulationStartRequestWarmup = new SimulationStartRequest(
    Instant.parse("2019-11-20T19:20:50.63Z"),
    Instant.parse("2019-11-20T19:25:50.63Z"),
    Some(Duration.ofSeconds(30)),
    3,
    new ProductVolume(Right("100.000000")),
    new QuoteVolume(Right("10000.00")),
    snapshotBufferSize = 3,
  )

  val checkpointedSimulationStartRequest = new SimulationStartRequest(
    Instant.parse("2019-11-20T19:20:50.63Z"),
    Instant.parse("2019-11-20T19:25:50.63Z"),
    Some(Duration.ofSeconds(30)),
    3,
    new ProductVolume(Right("100.000000")),
    new QuoteVolume(Right("10000.00")),
    snapshotBufferSize = 3,
  )

  val observationRequest = new ObservationRequest(10)
  val normalizeObservationRequest = new ObservationRequest(10, true)
}

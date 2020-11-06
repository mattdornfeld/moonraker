package co.firstorderlabs.coinbaseml.fakebase.TestData

import java.time.{Duration, Instant}

import co.firstorderlabs.common.currency.Configs.ProductPrice
import co.firstorderlabs.common.currency.Configs.ProductPrice.ProductVolume
import co.firstorderlabs.common.currency.Price.BtcUsdPrice.QuoteVolume
import co.firstorderlabs.common.protos.environment.ObservationRequest
import co.firstorderlabs.common.protos.fakebase._

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

  val fundsTooLargeOrderRequest = BuyMarketOrderRequest(
    QuoteVolume.maxVolume * Right(2.0),
    ProductPrice.productId
  )

  val fundsTooSmallOrderRequest = BuyMarketOrderRequest(
    QuoteVolume.zeroVolume,
    ProductPrice.productId
  )

  lazy val insufficientFundsOrderRequest = BuyMarketOrderRequest(
    simulationStartRequest.initialQuoteFunds * Right(2.0),
    ProductPrice.productId
  )

  val postOnlyOrderRequest = BuyLimitOrderRequest(
    new ProductPrice(Right("1000.000")),
    ProductPrice.productId,
    new ProductVolume(Right("1.00")),
    true
  )

  val priceTooLargeOrderRequest = BuyLimitOrderRequest(
    ProductPrice.maxPrice / Right(0.5),
    ProductPrice.productId,
    new ProductVolume(Right("1.00")),
  )

  val priceTooSmallOrderRequest = BuyLimitOrderRequest(
    ProductPrice.zeroPrice,
    ProductPrice.productId,
    new ProductVolume(Right("1.00")),
  )

  val sizeTooLargeOrderRequest = BuyLimitOrderRequest(
    new ProductPrice(Right("1000.000")),
    ProductPrice.productId,
    ProductVolume.maxVolume * Right(2.0),
  )

  val sizeTooSmallOrderRequest = BuyLimitOrderRequest(
    new ProductPrice(Right("1000.000")),
    ProductPrice.productId,
    ProductVolume.zeroVolume,
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

  val observationRequest = new ObservationRequest(10)
  val normalizeObservationRequest = new ObservationRequest(10, true)

  val simulationStartRequest = new SimulationStartRequest(
    Instant.parse("2019-11-20T19:20:50.63Z"),
    Instant.parse("2019-11-20T19:25:50.63Z"),
    Some(Duration.ofSeconds(30)),
    0,
    new ProductVolume(Right("100.000000")),
    new QuoteVolume(Right("10000.00")),
    snapshotBufferSize = 3,
    observationRequest = Some(observationRequest),
  )

  val simulationStartRequestWarmup = new SimulationStartRequest(
    Instant.parse("2019-11-20T19:20:50.63Z"),
    Instant.parse("2019-11-20T19:25:50.63Z"),
    Some(Duration.ofSeconds(30)),
    3,
    new ProductVolume(Right("100.000000")),
    new QuoteVolume(Right("10000.00")),
    snapshotBufferSize = 3,
    observationRequest = Some(observationRequest),
  )

  val checkpointedSimulationStartRequest = new SimulationStartRequest(
    Instant.parse("2019-11-20T19:20:50.63Z"),
    Instant.parse("2019-11-20T19:25:50.63Z"),
    Some(Duration.ofSeconds(30)),
    3,
    new ProductVolume(Right("100.000000")),
    new QuoteVolume(Right("10000.00")),
    snapshotBufferSize = 3,
    observationRequest = Some(observationRequest),
  )
}

package co.firstorderlabs.coinbaseml.fakebase.TestData

import java.time.{Duration, Instant}

import co.firstorderlabs.common.currency.Configs.ProductPrice
import co.firstorderlabs.common.currency.Configs.ProductPrice.ProductVolume
import co.firstorderlabs.common.currency.Price.BtcUsdPrice.QuoteVolume
import co.firstorderlabs.common.protos.environment.ObservationRequest
import co.firstorderlabs.common.protos.fakebase._
import co.firstorderlabs.common.types.Types.SimulationId

object RequestsData {
  def buyLimitOrderRequest(simulationId: SimulationId): BuyLimitOrderRequest =
    new BuyLimitOrderRequest(
      new ProductPrice(Right("1000.00")),
      ProductPrice.productId,
      new ProductVolume(Right("1.000000")),
      false,
      Some(Duration.ofDays(10)),
      simulationId = Some(simulationId)
    )

  def buyMarketOrderRequest(simulationId: SimulationId): BuyMarketOrderRequest =
    new BuyMarketOrderRequest(
      new QuoteVolume(Right("1000.00")),
      ProductPrice.productId,
      simulationId = Some(simulationId)
    )

  def fundsTooLargeOrderRequest(
      simulationId: SimulationId
  ): BuyMarketOrderRequest =
    BuyMarketOrderRequest(
      QuoteVolume.maxVolume * Right(2.0),
      ProductPrice.productId,
      simulationId = Some(simulationId)
    )

  def fundsTooSmallOrderRequest(
      simulationId: SimulationId
  ): BuyMarketOrderRequest =
    BuyMarketOrderRequest(
      QuoteVolume.zeroVolume,
      ProductPrice.productId,
      simulationId = Some(simulationId)
    )

  def insufficientFundsOrderRequest(
      simulationId: SimulationId
  ): BuyMarketOrderRequest =
    BuyMarketOrderRequest(
      simulationStartRequest.initialQuoteFunds * Right(2.0),
      ProductPrice.productId,
      simulationId = Some(simulationId)
    )

  def postOnlyOrderRequest(simulationId: SimulationId): BuyLimitOrderRequest =
    BuyLimitOrderRequest(
      new ProductPrice(Right("1000.000")),
      ProductPrice.productId,
      new ProductVolume(Right("1.00")),
      true,
      simulationId = Some(simulationId)
    )

  def priceTooLargeOrderRequest(
      simulationId: SimulationId
  ): BuyLimitOrderRequest =
    BuyLimitOrderRequest(
      ProductPrice.maxPrice / Right(0.5),
      ProductPrice.productId,
      new ProductVolume(Right("1.00")),
      simulationId = Some(simulationId)
    )

  def priceTooSmallOrderRequest(
      simulationId: SimulationId
  ): BuyLimitOrderRequest =
    BuyLimitOrderRequest(
      ProductPrice.zeroPrice,
      ProductPrice.productId,
      new ProductVolume(Right("1.00")),
      simulationId = Some(simulationId)
    )

  def sizeTooLargeOrderRequest(
      simulationId: SimulationId
  ): BuyLimitOrderRequest =
    BuyLimitOrderRequest(
      new ProductPrice(Right("1000.000")),
      ProductPrice.productId,
      ProductVolume.maxVolume * Right(2.0),
      simulationId = Some(simulationId)
    )

  def sizeTooSmallOrderRequest(
      simulationId: SimulationId
  ): BuyLimitOrderRequest =
    BuyLimitOrderRequest(
      new ProductPrice(Right("1000.000")),
      ProductPrice.productId,
      ProductVolume.zeroVolume,
      simulationId = Some(simulationId)
    )

  def orderBooksRequest(simulationId: SimulationId) =
    new OrderBooksRequest(10, simulationId = Some(simulationId))

  def sellLimitOrderRequest(simulationId: SimulationId): SellLimitOrderRequest =
    new SellLimitOrderRequest(
      new ProductPrice(Right("1001.00")),
      ProductPrice.productId,
      new ProductVolume(Right("1.000000")),
      false,
      Some(Duration.ofDays(10)),
      simulationId = Some(simulationId)
    )

  def sellMarketOrderRequest(
      simulationId: SimulationId
  ): SellMarketOrderRequest =
    new SellMarketOrderRequest(
      ProductPrice.productId,
      new ProductVolume(Right("1.000000")),
      simulationId = Some(simulationId)
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
    stopInProgressSimulations = true
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
    stopInProgressSimulations = true
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
    stopInProgressSimulations = true
  )
}

package co.firstorderlabs.moonraker.interactive

import co.firstorderlabs.coinbaseml.common.utils.Utils.FutureUtils
import co.firstorderlabs.coinbaseml.fakebase.{
  Exchange,
  MatchingEngine,
  SimulationState
}
import co.firstorderlabs.common.currency.Price.BtcUsdPrice.{
  ProductVolume,
  QuoteVolume
}
import co.firstorderlabs.common.protos.actionizers.{
  Actionizer,
  BollingerOnBookVolumeConfigs,
  BollingerOnBookVolumeState
}
import co.firstorderlabs.common.protos.environment.{
  ActionRequest,
  ObservationRequest
}
import co.firstorderlabs.common.protos.fakebase.{
  SimulationStartRequest,
  SimulationType,
  StepRequest
}
import co.firstorderlabs.common.protos.indicators.{
  KaufmanAdaptiveMovingAverageConfigs,
  KaufmanAdaptiveMovingVarianceConfigs,
  SampleValueByBarIncrementConfigs
}
import co.firstorderlabs.common.types.Utils.OptionUtils
import co.firstorderlabs.moonraker.interactive.InteractiveUtils.{
  FeatureEngineeringUtils,
  PlottingUtils,
  TraceUtils,
  axesList
}

import java.time.{Duration, Instant}

object BollingerOnBookVolumeVisualization extends App {
  val actionRequest =
    ActionRequest(actionizer = Actionizer.BollingerOnBookVolume)
  val actionizerConfigs = BollingerOnBookVolumeConfigs(
    smoothedOnBookVolume = KaufmanAdaptiveMovingAverageConfigs(750),
    priceMovingVariance =
      KaufmanAdaptiveMovingVarianceConfigs(movingAverageConfigs =
        KaufmanAdaptiveMovingAverageConfigs(100)
      ),
    sampledOnBookVolumeDerivative =
      SampleValueByBarIncrementConfigs(barSize = 10000000),
    bollingerBandSize = 1.0,
    onBookVolumeChangeBuyThreshold = -100e3,
    onBookVolumeChangeSellThreshold = 100e3
  )

  val simulationStartRequest = new SimulationStartRequest(
    actionRequest = Some(actionRequest),
    actionizerConfigs = actionizerConfigs.toSealedOneOf,
    enableProgressBar = false,
    endTime = Instant.parse("2020-11-26T00:00:00.00Z"),
    initialProductFunds = new ProductVolume(Right("0.000000")),
    initialQuoteFunds = new QuoteVolume(Right("10000.00")),
    numWarmUpSteps = 3,
    observationRequest = Some(new ObservationRequest),
    simulationType = SimulationType.evaluation,
    startTime = Instant.parse("2020-11-18T00:00:00.00Z"),
    stopInProgressSimulations = true,
    timeDelta = Some(Duration.ofSeconds(30))
  )

  val simulationInfo = Exchange.start(simulationStartRequest).get

  val simulationId = simulationInfo.exchangeInfo.get.simulationId.get
  val simulationState = SimulationState.getOrFail(simulationId)
  implicit val matchingEngineState = simulationState.matchingEngineState
  implicit val simulationMetadata = simulationState.simulationMetadata
  implicit val walletState = simulationState.accountState.walletsState

  val observationRequest = ObservationRequest(simulationId = Some(simulationId))
  val stepRequest = StepRequest(
    actionRequest = Some(actionRequest.update(_.simulationId := simulationId)),
    simulationId = Some(simulationId)
  )

  val aggregates = (1 to 20000).map { i =>
    Exchange.step(stepRequest)
    if (i % 100 == 0) {
      println(s"step: $i")
    }
    val actionizerState = simulationState.environmentState.actionizerState match {
      case state: BollingerOnBookVolumeState => state
      case state => throw new IllegalStateException(s"${state} is not of type BollingerOnBookVolumeState")
    }

    (
      MatchingEngine.calcMidPrice,
      actionizerState,
      MatchingEngine.calcPortfolioValue
    )
  }

  val midPrice = aggregates.map(_._1)
  val upperBollingerBand = aggregates.map(
    _._2.upperBollingerBand
  )
  val lowerBollingerBand = aggregates.map(
    _._2.lowerBollingerBand
  )
  val smoothedOnBookVolumeChange = aggregates.map(
    _._2.smoothedOnBookVolumeChange
  )
  val signal = aggregates.map(_._2.signal)
  val roi = aggregates.map(_._3).percentChange.map(_ / 0.1)

  Seq(
    roi.scatter("roi", axesList(3).some),
    upperBollingerBand
      .scatter(
        "upperBollingerBand",
        axesList(2).some
      ),
    midPrice.scatter("midPrice", axesList(2).some),
    lowerBollingerBand
      .scatter(
        "lowerBollingerBand",
        axesList(2).some
      ),
    smoothedOnBookVolumeChange
      .scatter(
        "smoothedOnBookVolumeChange",
        axesList(1).some
      ),
    signal.scatter("signal", axesList(0).some)
  ).htmlSubPlots(
    path = "figures/BollingerOnBookVolumeVisualization.html",
    xLabels = Seq("timesteps".some, None, None, None),
    yLabels = Seq(
      "signal".some,
      "dollars / timestep".some,
      "dollars".some,
      "percentagePoints".some
    ),
    yRanges = Seq(
      None,
      (-1e6, 1e6).some,
      (17e3, 19.5e3).some,
      None
    ),
    title = "Kaufman Bollinger Band/OBV Buy Signals"
  )
}

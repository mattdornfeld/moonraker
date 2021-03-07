package co.firstorderlabs.moonraker.interactive

import co.firstorderlabs.coinbaseml.common.actions.actionizers.BollingerOnBookVolumeState
import co.firstorderlabs.coinbaseml.common.utils.Utils.FutureUtils
import co.firstorderlabs.coinbaseml.fakebase.{Exchange, MatchingEngine, SimulationState}
import co.firstorderlabs.common.currency.Price.BtcUsdPrice.{ProductVolume, QuoteVolume}
import co.firstorderlabs.common.protos.environment.{ActionRequest, Actionizer, ObservationRequest}
import co.firstorderlabs.common.protos.fakebase.{SimulationStartRequest, SimulationType, StepRequest}
import co.firstorderlabs.moonraker.interactive.InteractiveUtils.{OptionUtils, PlottingUtils, TraceUtils, axesList}

import java.time.{Duration, Instant}

object BollingerOnBookVolumeVisualization extends App {
  val actionRequest =
    ActionRequest(actionizer = Actionizer.BollingerOnBookVolume)
  val actionizerConfigKeys = BollingerOnBookVolumeState.ActionizerConfigsKeys
  val simulationStartRequest = new SimulationStartRequest(
    actionRequest = Some(actionRequest),
    actionizerConfigs = Map(
      actionizerConfigKeys.bollingerBandSize -> 1.0,
      actionizerConfigKeys.bollingerBandWindowSize -> 100.0,
      actionizerConfigKeys.onBookVolumeWindowSize -> 750.0,
      actionizerConfigKeys.onBookVolumeChangeBuyThreshold -> -100e3,
      actionizerConfigKeys.volumeBarSize -> 10000000.0
    ),
    enableProgressBar = false,
    endTime = Instant.parse("2020-11-26T00:00:00.00Z"),
    initialProductFunds = new ProductVolume(Right("1.000000")),
    initialQuoteFunds = new QuoteVolume(Right("10000.00")),
    numWarmUpSteps = 3,
    observationRequest = Some(new ObservationRequest),
    simulationType = SimulationType.evaluation,
    snapshotBufferSize = 10,
    startTime = Instant.parse("2020-11-18T00:00:00.00Z"),
    stopInProgressSimulations = true,
    timeDelta = Some(Duration.ofSeconds(30))
  )

  val simulationInfo = Exchange.start(simulationStartRequest).get
  val simulationId = simulationInfo.exchangeInfo.get.simulationId.get

  val observationRequest = ObservationRequest(simulationId = Some(simulationId))

  val stepRequest = StepRequest(
    actionRequest = Some(actionRequest.update(_.simulationId := simulationId)),
    simulationId = Some(simulationId)
  )

  val simulationState = SimulationState.getOrFail(simulationId)
  implicit val matchingEngineState = simulationState.matchingEngineState

  val aggregates = (1 to 10).map { i =>
    Exchange.step(stepRequest)
    if (i % 100 == 0) {
      println(s"step: $i")
    }
    val actionizerState =
      simulationState.environmentState.actionizerState.getState

    (
      MatchingEngine.calcMidPrice,
      actionizerState
    )
  }

  val actionizerStateKeys = BollingerOnBookVolumeState.ActionizerStateKeys
  val midPrice = aggregates.map(_._1)
  val lowerBollingerBand = aggregates.map(
    _._2(actionizerStateKeys.lowerBollingerBand)
  )
  val smoothedOnBookVolumeChange = aggregates.map(
    _._2(
      actionizerStateKeys.smoothedOnBookVolumeChange
    )
  )
  val signal = aggregates.map(_._2(actionizerStateKeys.signal))

  Seq(
    midPrice.scatter("midPrice", Some(axesList(2))),
    lowerBollingerBand
      .scatter(
        actionizerStateKeys.lowerBollingerBand,
        axesList(2).some
      ),
    smoothedOnBookVolumeChange
      .scatter(
        actionizerStateKeys.smoothedOnBookVolumeChange,
        axesList(1).some
      ),
    signal.scatter(actionizerStateKeys.signal, axesList(0).some)
  ).htmlSubPlots(
    path = "figures/BollingerOnBookVolumeVisualization.html",
    xLabels = Seq("timesteps".some, None, None),
    yLabels = Seq("signal".some, "dollars / second".some, "dollars".some),
    yRanges = Seq(None, Some(-1e6, 1e6), Some(17e3, 19.5e3)),
    title = "Kaufman Bollinger Band/OBV Buy Signals"
  )
}

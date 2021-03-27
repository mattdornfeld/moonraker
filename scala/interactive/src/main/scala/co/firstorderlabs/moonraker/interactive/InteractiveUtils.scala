package co.firstorderlabs.moonraker.interactive

import almond.interpreter.api.OutputHandler
import co.firstorderlabs.coinbaseml.common.actions.actionizers.Indicators.{
  ExponentialMovingAverage,
  ExponentialMovingVariance,
  KaufmanAdaptiveMovingAverage,
  KaufmanAdaptiveMovingVariance,
  SampleValueByBarIncrement
}
import co.firstorderlabs.coinbaseml.common.utils.Utils.{Interval, When}
import co.firstorderlabs.common.protos.indicators.{
  ExponentialMovingAverageConfigs,
  ExponentialMovingAverageState,
  ExponentialMovingVarianceConfigs,
  ExponentialMovingVarianceState,
  KaufmanAdaptiveMovingAverageConfigs,
  KaufmanAdaptiveMovingAverageState,
  KaufmanAdaptiveMovingVarianceConfigs,
  KaufmanAdaptiveMovingVarianceState,
  SampleValueByBarIncrementConfigs,
  SampleValueByBarIncrementState
}
import plotly.element.{AxisAnchor, AxisReference}
import plotly.layout.{Axis, Layout}
import plotly.{Almond, Range, Scatter, Trace}

import java.io.File

object InteractiveUtils {
  final case class Axes(xAxis: AxisReference, yAxis: AxisReference) {
    val xAxisAnchor = AxisAnchor.Reference(xAxis)
    val yAxisAnchor = AxisAnchor.Reference(yAxis)
  }
  val axesList =
    List(
      Axes(AxisReference.X1, AxisReference.Y1),
      Axes(AxisReference.X2, AxisReference.Y2),
      Axes(AxisReference.X3, AxisReference.Y3),
      Axes(AxisReference.X4, AxisReference.Y4)
    )

  implicit class TraceUtils[A <: Trace](seq: Seq[A]) {
    def notebookPlot(
        xLabel: String = "",
        yLabel: String = "",
        xRange: Option[(Double, Double)] = None,
        yRange: Option[(Double, Double)] = None,
        title: String = ""
    )(implicit
        publish: OutputHandler
    ): String = {
      val xAxis = Axis()
        .when(xLabel.size > 0)(_.withTitle(xLabel))
        .when(xRange.nonEmpty)(_.withRange(Range.Doubles(xRange.get)))
      val yAxis = Axis()
        .when(yLabel.size > 0)(_.withTitle(yLabel))
        .when(yRange.nonEmpty)(_.withRange(Range.Doubles(yRange.get)))

      val layout = (new Layout)
        .withXaxis(xAxis)
        .withYaxis(yAxis)
        .withTitle(title)

      Almond.plot(seq, layout)
    }

    def htmlPlot(
        path: String,
        xLabel: String = "",
        yLabel: String = "",
        xRange: Option[(Double, Double)] = None,
        yRange: Option[(Double, Double)] = None,
        title: String = ""
    ): Unit = {
      import plotly.Plotly.TraceSeqOps
      val xAxis = Axis()
        .when(xLabel.size > 0)(_.withTitle(xLabel))
        .when(xRange.nonEmpty)(_.withRange(Range.Doubles(xRange.get)))
      val yAxis = Axis()
        .when(yLabel.size > 0)(_.withTitle(yLabel))
        .when(yRange.nonEmpty)(_.withRange(Range.Doubles(yRange.get)))

      val layout = (new Layout)
        .withXaxis1(xAxis)
        .withYaxis1(yAxis)
        .withTitle(title)

      new File(path).delete
      seq.plot(path, layout)
    }

    def htmlSubPlots(
        path: String,
        xLabels: Seq[Option[String]] = Seq(),
        yLabels: Seq[Option[String]] = Seq(),
        xRanges: Seq[Option[(Double, Double)]] = Seq(),
        yRanges: Seq[Option[(Double, Double)]] = Seq(),
        title: String = ""
    ): Unit = {
      import plotly.Plotly.TraceSeqOps
      val axes: Seq[(Axis, Axis)] = xLabels
        .zipAll(yLabels, None, None)
        .zipAll(xRanges, (None, None), None)
        .zipAll(yRanges, ((None, None), None), None)
        .zip(axesList)
        .map {
          case (
                (
                  (
                    (xLabel: Option[String], yLabel: Option[String]),
                    xRange: Option[(Double, Double)]
                  ),
                  yRange: Option[(Double, Double)]
                ),
                axes: Axes
              ) => {
            val xAxis = Axis()
              .withAnchor(axes.yAxisAnchor)
              .when(xRange.isDefined)(_.withRange(xRange.get))
              .when(xLabel.isDefined)(_.withTitle(xLabel.get))
            val yAxis = Axis()
              .withAnchor(axes.xAxisAnchor)
              .when(yRange.isDefined)(_.withRange(yRange.get))
              .when(yLabel.isDefined)(_.withTitle(yLabel.get))
            (xAxis, yAxis)
          }
        }

      var layout = (new Layout).withTitle(title).withHeight(1000)
      val domainHeight = 1.0 / axes.size
      val yAxisDomains = (1 to axes.size).map(i =>
        ((i - 1) * domainHeight, i * domainHeight - 0.1)
      )
      axes.zipWithIndex.foreach {
        case ((xAxis, yAxis), 0) => {
          layout = layout
            .withXaxis1(xAxis)
            .withYaxis1(yAxis.withDomain(yAxisDomains(0)))
        }
        case ((xAxis, yAxis), 1) => {
          layout = layout
            .withXaxis2(xAxis)
            .withYaxis2(yAxis.withDomain(yAxisDomains(1)))
        }
        case ((xAxis, yAxis), 2) => {
          layout = layout
            .withXaxis3(xAxis)
            .withYaxis3(yAxis.withDomain(yAxisDomains(2)))
        }
        case ((xAxis, yAxis), 3) => {
          layout = layout
            .withXaxis4(xAxis)
            .withYaxis4(yAxis.withDomain(yAxisDomains(3)))
        }
      }
      new File(path).delete
      seq.plot(path, layout, false, true, true)
    }
  }

  implicit class PlottingUtils(seq: Seq[Double]) {
    def scatter(name: String = "", axes: Option[Axes] = None): Scatter = {
      Scatter()
        .when(name.size > 0)(_.withName(name))
        .withX(1 to seq.size)
        .withY(seq)
        .when(axes.isDefined)(_.withXaxis(axes.get.xAxis))
        .when(axes.isDefined)(_.withYaxis(axes.get.yAxis))
    }
  }

  implicit class FeatureEngineeringUtils(seq: Seq[Double]) {
    def kaufmanBollingerBands(
        bandSize: Double,
        windowSize: Int
    ): (Seq[Double], Seq[Double]) = {
      val movingAverage =
        seq.kaufmanAdaptiveMovingAverage(windowSize)
      val movingStd = seq
        .kaufmanAdaptiveMovingVariance(windowSize)
        .map(p => math.pow(p, 0.5))
      val upperBand =
        movingAverage.zip(movingStd).map(i => i._1 + bandSize * i._2)
      val lowerBand =
        movingAverage.zip(movingStd).map(i => i._1 - bandSize * i._2)

      (lowerBand, upperBand)
    }

    def cumSum: Seq[Double] =
      seq.map { var sum = 0.0; e => { sum += e; sum } }

    def derivative(delta: Double = 1.0): Seq[Double] =
      pairWithNextElement(seq).map(i => (i._2 - i._1) / delta)

    def exponentialMovingAverage(windowSize: Int): Seq[Double] = {
      implicit val configs = ExponentialMovingAverageConfigs(windowSize)
      implicit val state = new ExponentialMovingAverageState
      ExponentialMovingAverage.transform(seq)._1
    }

    def exponentialMovingVariance(windowSize: Int): Seq[Double] = {
      implicit val configs = ExponentialMovingVarianceConfigs(
        ExponentialMovingAverageConfigs(windowSize)
      )
      implicit val state = ExponentialMovingVarianceState(movingAverageState =
        new ExponentialMovingAverageState
      )
      ExponentialMovingVariance.transform(seq)._1
    }

    def kaufmanAdaptiveMovingAverage(windowSize: Int): Seq[Double] = {
      implicit val configs = KaufmanAdaptiveMovingAverageConfigs(windowSize)
      implicit val state = new KaufmanAdaptiveMovingAverageState
      KaufmanAdaptiveMovingAverage.transform(seq)._1
    }

    def kaufmanAdaptiveMovingVariance(windowSize: Int): Seq[Double] = {
      implicit val configs = KaufmanAdaptiveMovingVarianceConfigs(
        KaufmanAdaptiveMovingAverageConfigs(windowSize)
      )
      implicit val state =
        KaufmanAdaptiveMovingVarianceState(movingAverageState =
          new KaufmanAdaptiveMovingAverageState
        )
      KaufmanAdaptiveMovingVariance.transform(seq)._1
    }

    def pairWithNextElement[A <: AnyVal](seq: Seq[A]): Seq[(A, A)] =
      seq.dropRight(1).zip(seq.drop(1))

    /** Expressed the seq as a function of the percent changed since
      *
      * @return
      */
    def percentChange: Seq[Double] =
      seq.map { val head = seq.head; d => (d - head) / head }

    def rescale(
        min: Option[Double] = None,
        max: Option[Double] = None
    ): Seq[Double] = {
      val _min = min.getOrElse(seq.min)
      val _max = max.getOrElse(seq.max)
      val normalizationFactor = _max - _min
      seq.map(e =>
        if (normalizationFactor != 0.0) (e - _min) / normalizationFactor
        else 0.0
      )
    }

    def averageByIncrementsOf(
        y: Seq[Double],
        increment: Double
    ): Seq[Double] = {
      val yStepIncrements =
        y.cumSum.map(v => math.round(v / increment) * increment)

      val incrementEndPoints = List(0) ++ yStepIncrements
        .derivative()
        .zipWithIndex
        .filter(i => i._1 > 0)
        .map(_._2) ++ List(yStepIncrements.size + 1)

      val intervals: Seq[Interval] = pairWithNextElement(incrementEndPoints)
        .map(i => Interval(i._1, i._2, Interval.IntervalType.halfOpenRight))

      seq.zipWithIndex
        .groupMap(i =>
          intervals.filter(interval => interval.contains(i._2)).head
        )(_._1)
        .map(i => (i._1, i._2.simpleMovingAverage(1, i._2.head)))
        .toList
        .sortBy(_._1.minValue)
        .map(_._2)
        .flatten
    }

    def sampleByBarIncrement(
        barSeries: Seq[Double],
        barSize: Long
    ): Seq[Double] = {
      require(seq.size == barSeries.size)
      val configs = SampleValueByBarIncrementConfigs(barSize)
      seq.zip(barSeries).map {
        var state = new SampleValueByBarIncrementState
        i =>
          state = SampleValueByBarIncrement.update(i._1, i._2)(configs, state)
          state.value
      }

    }

    /** Sample seq everytime the cumSum of y passed a threshold of increment. Useful for smoothing noisy time series.
      *
      * @param y
      * @param increment
      * @return
      */
    def sampleByIncrementsOf(y: Seq[Double], increment: Double): Seq[Double] = {
      val yStepIncrements =
        y.cumSum.map(v => math.round(v / increment) * increment)

      val sampleIndices = List(0) ++ yStepIncrements
        .derivative()
        .zipWithIndex
        .filter(i => i._1 > 0)
        .map(_._2) ++ List(yStepIncrements.size + 1)

      val seqArray = seq.toArray
      pairWithNextElement(sampleIndices)
        .map(i => List.fill(i._2 - i._1)(seqArray(i._1)))
        .flatten
    }

    def simpleMovingAverage(n: Int, initialValue: Double = 0.0): Seq[Double] = {
      require(n <= seq.size, "n must be <= size of sequence")
      seq.zipWithIndex.map {
        var movingAverage = initialValue
        item => {
          val nextSample = item._1
          val index = item._2
          val oldestSample = if (index - n >= 0) seq(index - n) else 0.0
          movingAverage += (nextSample - oldestSample) / n
          movingAverage
        }
      }
    }
  }
}

package co.firstorderlabs.moonraker.interactive

import almond.interpreter.api.OutputHandler
import co.firstorderlabs.coinbaseml.common.actions.actionizers.Indicators.{
  ExponentialMovingAverage,
  ExponentialMovingVariance,
  KaufmanAdaptiveMovingAverage,
  KaufmanAdaptiveMovingVariance
}
import co.firstorderlabs.coinbaseml.common.utils.Utils.{Interval, When}
import plotly._
import plotly.layout.{Axis, Layout}

object InteractiveUtils {
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
  }

  implicit class PlottingUtils(seq: Seq[Double]) {
    def scatter(name: String = ""): Scatter = {
      Scatter()
        .when(name.size > 0)(_.withName(name))
        .withX(1 to seq.size)
        .withY(seq)
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
      val alpha = 2.0 / (windowSize + 1)
      ExponentialMovingAverage(alpha, 0.0, 0.0).transform(seq)
    }

    def exponentialMovingVariance(alpha: Double): Seq[Double] =
      ExponentialMovingVariance(alpha).transform(seq)

    def kaufmanAdaptiveMovingAverage(n: Int): Seq[Double] =
      KaufmanAdaptiveMovingAverage(n).transform(seq)

    def kaufmanAdaptiveMovingVariance(n: Int): Seq[Double] =
      KaufmanAdaptiveMovingVariance(n).transform(seq)

    def pairWithNextElement[A <: AnyVal](seq: Seq[A]): Seq[(A, A)] =
      seq.dropRight(1).zip(seq.drop(1))

    /** Expressed the seq as a function of the percent changed since
      *
      * @return
      */
    def percentChange: Seq[Double] =
      seq.map { val head = seq.head; d => (d - head) / head }

    def rescale: Seq[Double] = {
      val min = seq.min
      val max = seq.max
      val normalizationFactor = max - min
      seq.map(e => (e - min) / normalizationFactor)
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

      seq
        .zipWithIndex
        .groupMap(i => intervals.filter(interval => interval.contains(i._2)).head)(_._1)
        .map(i => (i._1, i._2.simpleMovingAverage(1, i._2.head)))
        .toList
        .sortBy(_._1.minValue)
        .map(_._2)
        .flatten
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

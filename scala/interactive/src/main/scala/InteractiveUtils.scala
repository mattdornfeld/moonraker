package co.firstorderlabs.moonraker.interactive

import almond.interpreter.api.OutputHandler
import co.firstorderlabs.coinbaseml.common.utils.Utils.When
import plotly._
import plotly.layout.{Axis, Layout}

object InteractiveUtils {
  implicit class TraceUtils[A <: Trace](seq: Seq[A]) {
    def notebookPlot(
        xLabel: String = "",
        yLabel: String = "",
        xRange: Option[(Double, Double)] = None,
        yRange: Option[(Double, Double)] = None
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
    def cumSum: Seq[Double] =
      seq.map { var sum = 0.0; e => { sum += e; sum } }

    def exponentialMovingAverage(alpha: Double): Seq[Double] =
      seq.map {
        var movingAverage = 0.0
        nextSample => {
          movingAverage += alpha * (nextSample - movingAverage)
          movingAverage
        }
      }

    def rescale: Seq[Double] = {
      val min = seq.min
      val max = seq.max
      val normalizationFactor = max - min
      seq.map(e => (e - min) / normalizationFactor)
    }

    def simpleMovingAverage(n: Int): Seq[Double] = {
      require(n <= seq.size, "n must be <= size of sequence")
      seq.zipWithIndex.map {
        var movingAverage = 0.0
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

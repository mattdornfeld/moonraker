import CoinbasemlDependencies.coinbasemlDependencies
import sbt._

object InteractiveDependencies {
  val plotlyVersion = "0.8.0"
  val interactiveDependencies: Seq[ModuleID] = coinbasemlDependencies ++ Seq(
    "org.plotly-scala" % "plotly-almond_2.13" % plotlyVersion,
    "org.plotly-scala" % "plotly-render_2.13" % plotlyVersion,
    "sh.almond" % "interpreter_2.13" % "0.10.9"
  )
}

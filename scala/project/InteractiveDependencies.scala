import CoinbasemlDependencies.coinbasemlDependencies
import sbt._

object InteractiveDependencies {
  val interactiveDependencies: Seq[ModuleID] = coinbasemlDependencies ++ Seq(
    "org.plotly-scala" % "plotly-almond_2.13" % "0.8.0",
    "sh.almond" % "interpreter_2.13" % "0.10.9"
  )
}

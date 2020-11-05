package co.firstorderlabs.coinbaseml.common.featurizers

import co.firstorderlabs.common.types.Events.Event

import scala.collection.mutable
import scala.math.pow

object Aggregators {
  type State = mutable.HashMap[String, Double]

  abstract class EventAggregator {
    val eventFilter: Event => Boolean
    val eventMapper: Event => Double
    protected val state = new State

    def clear: Unit
    def update(event: Event): Unit
    def value: Double
  }

  case class Counter(
      eventFilter: Event => Boolean,
      eventMapper: Event => Double
  ) extends EventAggregator {
    clear

    def clear: Unit = {
      state.clear
      state.put("count", 0.0)
    }

    def update(event: Event): Unit = {
      if (eventFilter(event)) {
        state("count") += 1
      }
    }

    def value: Double = state("count")
  }

  case class RunningMean(
      eventFilter: Event => Boolean,
      eventMapper: Event => Double
  ) extends EventAggregator {
    clear

    def clear: Unit = {
      state.clear
      state.put("mean", 0.0)
      state.put("numSamples", 0)
    }

    def update(event: Event): Unit = {
      if (eventFilter(event)) {
        state("numSamples") += 1
        state("mean") += (eventMapper(event) - state("mean")) / state(
          "numSamples"
        )
      }
    }

    def value: Double = state("mean")
  }

  case class RunningStandardDeviation(
      eventFilter: Event => Boolean,
      eventMapper: Event => Double
  ) extends EventAggregator {
    clear

    def clear: Unit = {
      state.clear
      state.put("m", 0.0)
      state.put("s", 0.0)
      state.put("numSamples", 0)
      state.put("variance", 0.0)
    }

    def update(event: Event): Unit = {
      if (eventFilter(event)) {
        state("numSamples") += 1
        val sample = eventMapper(event)
        if (state("numSamples") == 1) {
          state("m") = sample
          state("variance") = 0.0
        } else {
          val mOld = state("m")
          state("m") += (sample - mOld) / state("numSamples")
          state("s") += (sample - mOld) * (sample - state("m"))
          state("variance") = state("s") / (state("numSamples") - 1)
        }
      }
    }

    def value: Double = pow(state("variance"), 0.5)
  }
}

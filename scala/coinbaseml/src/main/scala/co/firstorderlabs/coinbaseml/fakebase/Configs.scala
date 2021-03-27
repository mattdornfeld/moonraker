package co.firstorderlabs.coinbaseml.fakebase

import scala.util.Properties.envOrElse

object Configs {
  val localMode = envOrElse("LOCAL_MODE", "false").toBoolean
}

import CommonDependencies.commonDependencies
import sbt._

object BqWriterDependencies {
  val googleCloudBigqueryVersion = "1.124.2"
  val xchangeVersion = "5.0.3"

  val bqWriterDependencies: Seq[ModuleID] = commonDependencies ++ Seq(
    "org.knowm.xchange" % "xchange-stream-coinbasepro" % xchangeVersion,
    "com.google.cloud" % "google-cloud-bigquery" % googleCloudBigqueryVersion exclude("com.google.guava", "guava")
  )
}


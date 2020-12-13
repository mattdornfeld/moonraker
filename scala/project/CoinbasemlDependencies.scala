import CommonDependencies.commonDependencies
import sbt._

object CoinbasemlDependencies {
  val apacheArrowVersion = "1.0.1"
  val catsVersion = "2.0.0"
  val catsRetryVersion = "2.0.0"
  val doobieVersion = "0.9.0"
  val googleCloudStorageVersion = "1.113.5"
  val rocksDbVersion = "6.13.3"

  val coinbasemlDependencies: Seq[ModuleID] = commonDependencies ++ Seq(
    "com.github.cb372" %% "cats-retry" % catsRetryVersion,
    "com.google.cloud" % "google-cloud-storage" % googleCloudStorageVersion,
    "com.google.cloud" % "google-cloud-bigquery" % "1.126.1",
    "com.google.cloud" % "google-cloud-bigquerystorage" % "1.7.0",
    "io.grpc" % "grpc-netty" % "1.34.0",
    "org.rocksdb" % "rocksdbjni" % rocksDbVersion,
    "org.apache.arrow" % "arrow-algorithm" % apacheArrowVersion,
    "org.apache.arrow" % "arrow-memory-netty" % apacheArrowVersion,
    "org.tpolecat" %% "doobie-core" % doobieVersion,
    "org.tpolecat" %% "doobie-hikari" % doobieVersion,
    "org.tpolecat" %% "doobie-postgres"  % doobieVersion,
    "org.typelevel" %% "cats-core" % catsVersion
  )
}

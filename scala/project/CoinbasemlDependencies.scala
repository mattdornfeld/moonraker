import CommonDependencies.commonDependencies
import sbt._

object CoinbasemlDependencies {
  val apacheArrowVersion = "1.0.1"
  val booPickleVersion = "1.3.2"
  val catsVersion = "2.0.0"
  val catsRetryVersion = "2.0.0"
  val doobieVersion = "0.9.0"
  val googleCloudBigQueryVersion = "1.126.1"
  val googleCloudBigQueryStorageVersion = "1.7.0"
  val googleCloudNioVersion = "0.122.3"
  val googleCloudStorageVersion = "1.113.5"
  val grpcNettyVersion = "1.34.0"
  val rocksDbVersion = "6.13.3"

  val coinbasemlDependencies: Seq[ModuleID] = commonDependencies ++ Seq(
    "com.github.cb372" %% "cats-retry" % catsRetryVersion,
    "com.google.cloud" % "google-cloud-bigquery" % googleCloudBigQueryVersion,
    "com.google.cloud" % "google-cloud-bigquerystorage" % googleCloudBigQueryStorageVersion,
    "com.google.cloud" % "google-cloud-nio" % googleCloudNioVersion,
    "com.google.cloud" % "google-cloud-storage" % googleCloudStorageVersion,
    "io.grpc" % "grpc-netty" % grpcNettyVersion,
    "io.suzaku" %% "boopickle" % booPickleVersion,
    "org.rocksdb" % "rocksdbjni" % rocksDbVersion,
    "org.apache.arrow" % "arrow-algorithm" % apacheArrowVersion,
    "org.apache.arrow" % "arrow-memory-netty" % apacheArrowVersion,
    "org.tpolecat" %% "doobie-core" % doobieVersion,
    "org.tpolecat" %% "doobie-hikari" % doobieVersion,
    "org.tpolecat" %% "doobie-postgres"  % doobieVersion,
    "org.typelevel" %% "cats-core" % catsVersion
  )
}

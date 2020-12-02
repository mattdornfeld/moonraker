import CommonDependencies.commonDependencies
import sbt._

object CoinbasemlDependencies {
  val apacheArrowVersion = "1.0.1"
  val catsVersion = "2.0.0"
  val catsRetryVersion = "2.0.0"
  val doobieVersion = "0.9.0"
  val rocksDbVersion = "6.13.3"

  val coinbasemlDependencies: Seq[ModuleID] = commonDependencies ++ Seq(
    "io.grpc" % "grpc-netty" % scalapb.compiler.Version.grpcJavaVersion,
    "org.rocksdb" % "rocksdbjni" % rocksDbVersion,
    "org.apache.arrow" % "arrow-algorithm" % apacheArrowVersion,
    "org.apache.arrow" % "arrow-memory-netty" % apacheArrowVersion,
    "org.tpolecat" %% "doobie-core" % doobieVersion,
    "org.tpolecat" %% "doobie-hikari" % doobieVersion,
    "org.tpolecat" %% "doobie-postgres"  % doobieVersion,
    "org.typelevel" %% "cats-core" % catsVersion,
    "com.github.cb372" %% "cats-retry" % catsRetryVersion
  )
}

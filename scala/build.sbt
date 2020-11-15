import CommonDependencies.commonDependencies
import BqWriterDependencies.bqWriterDependencies
import CoinbasemlDependencies.coinbasemlDependencies

organization in ThisBuild := "co.firstorderlabs"
scalaVersion in ThisBuild := "2.13.1"
scalacOptions in ThisBuild := Seq("-opt:l:method", "-opt:l:inline", "-opt-inline-from:co.firstorderlabs.**", "-opt-warnings")

lazy val global = project
  .in(file("."))
  .aggregate(
    common,
    bqwriter,
    coinbaseml,
  )
  .enablePlugins(AssemblyPlugin)

lazy val common = project
  .settings(
    name := "common",
    commonSettings,
    libraryDependencies ++= commonDependencies,
    PB.targets in Compile := Seq(scalapb.gen() -> (sourceManaged in Compile).value),
)

lazy val bqwriter = project
  .settings(
    name := "bqwriter",
    commonSettings,
    libraryDependencies ++= bqWriterDependencies,
    assemblyOutputPath in assembly := file("/tmp/moonraker/bqwriter/bqwriter.jar"),
    )
  .dependsOn(common)

lazy val coinbaseml = project
  .settings(
    name := "coinbaseml",
    commonSettings,
    libraryDependencies ++= coinbasemlDependencies,
    assemblyOutputPath in assembly := file("/tmp/moonraker/coinbaseml/coinbaseml.jar"),
    )
  .dependsOn(common)

lazy val commonSettings = Seq(
  version := "0.1.0-SNAPSHOT",
  parallelExecution in Test := false,
  assemblyMergeStrategy in assembly := {
    case "git.properties" => MergeStrategy.first
    case "META-INF/io.netty.versions.properties" => MergeStrategy.first
    case x if x.contains("netty") => MergeStrategy.first
    case x if x.contains("scalapb/option") => MergeStrategy.last
    case x if x.endsWith("module-info.class") => MergeStrategy.discard
    case x =>
        val oldStrategy = (assemblyMergeStrategy in assembly).value
        oldStrategy(x)
  },
  test in assembly := {},
)

resolvers in Global ++= Seq(
  "Sbt plugins"                   at "https://dl.bintray.com/sbt/sbt-plugin-releases",
  "Maven Central Server"          at "https://repo1.maven.org/maven2",
  "TypeSafe Repository Releases"  at "https://repo.typesafe.com/typesafe/releases/",
  "TypeSafe Repository Snapshots" at "https://repo.typesafe.com/typesafe/snapshots/",
  Resolver.sonatypeRepo("releases"),
)

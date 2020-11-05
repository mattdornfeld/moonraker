scalaVersion := "2.13.1"

lazy val commonSettings = Seq(
  name := "coinbaseml",
  organization := "co.firstorderlabs",
  version := "0.1.0",
  parallelExecution in Test := false,
  assemblyMergeStrategy in assembly := {
    case "git.properties" => MergeStrategy.first
    case "META-INF/io.netty.versions.properties" => MergeStrategy.first
    case x if x.contains("netty") => MergeStrategy.first
    case x if x.endsWith("module-info.class") => MergeStrategy.discard
    case x =>
        val oldStrategy = (assemblyMergeStrategy in assembly).value
        oldStrategy(x)
  },
  test in assembly := {},
)

lazy val app = (project in file("."))
  .settings(commonSettings: _*)
  .enablePlugins(AssemblyPlugin)

resolvers in Global ++= Seq(
  "Sbt plugins"                   at "https://dl.bintray.com/sbt/sbt-plugin-releases",
  "Maven Central Server"          at "https://repo1.maven.org/maven2",
  "TypeSafe Repository Releases"  at "https://repo.typesafe.com/typesafe/releases/",
  "TypeSafe Repository Snapshots" at "https://repo.typesafe.com/typesafe/snapshots/"
)

val apacheArrowVersion = "1.0.1"
val doobieVersion = "0.9.0"
val scalaTestVersion = "3.1.1"

resolvers ++= Seq(
  Resolver.sonatypeRepo("releases"),
)

libraryDependencies += "org.scalactic" %% "scalactic" % scalaTestVersion
libraryDependencies += "org.scalatest" %% "scalatest" % scalaTestVersion % "test"
libraryDependencies += "org.typelevel" %% "cats-core" % "2.0.0"

libraryDependencies ++= Seq(
  "org.knowm.xchange" % "xchange-stream-coinbasepro" % "5.0.3",
  "com.google.cloud" % "google-cloud-bigquery" % "1.124.1" exclude("com.google.guava", "guava"),
)

libraryDependencies ++= Seq(
  "org.apache.arrow" % "arrow-algorithm" % apacheArrowVersion,
  "org.apache.arrow" % "arrow-memory-netty" % apacheArrowVersion,
)

libraryDependencies ++= Seq(
  "org.tpolecat" %% "doobie-core" % doobieVersion,
  "org.tpolecat" %% "doobie-hikari" % doobieVersion,
  "org.tpolecat" %% "doobie-postgres"  % doobieVersion,
)

libraryDependencies ++= Seq(
    "io.grpc" % "grpc-netty" % scalapb.compiler.Version.grpcJavaVersion,
    "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf",
    "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion,
)

PB.targets in Compile := Seq(
  scalapb.gen() -> (sourceManaged in Compile).value
)

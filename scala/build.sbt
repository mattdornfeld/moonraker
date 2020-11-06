organization in ThisBuild := "co.firstorderlabs"
scalaVersion in ThisBuild := "2.13.1"

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
    libraryDependencies ++= commonDependencies ++ bqWriterDependencies,
    assemblyOutputPath in assembly := file("/tmp/moonraker/bqwriter/bqwriter.jar"),
    )
  .dependsOn(common)

lazy val coinbaseml = project
  .settings(
    name := "coinbaseml",
    commonSettings,
    libraryDependencies ++= commonDependencies ++ coinbasemlDependencies,
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

lazy val apacheArrowVersion = "1.0.1"
lazy val googleCloudBigqueryVersion = "1.124.2"
lazy val catsVersion = "2.0.0"
lazy val doobieVersion = "0.9.0"
lazy val scalaTestVersion = "3.1.1"
lazy val xchangeVersion = "5.0.3"

lazy val commonDependencies = Seq(
  "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf",
  "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion,
  "org.scalactic" %% "scalactic" % scalaTestVersion,
  "org.scalatest" %% "scalatest" % scalaTestVersion % "test",
)

lazy val coinbasemlDependencies = Seq(
  "io.grpc" % "grpc-netty" % scalapb.compiler.Version.grpcJavaVersion,
  "org.apache.arrow" % "arrow-algorithm" % apacheArrowVersion,
  "org.apache.arrow" % "arrow-memory-netty" % apacheArrowVersion,
  "org.tpolecat" %% "doobie-core" % doobieVersion,
  "org.tpolecat" %% "doobie-hikari" % doobieVersion,
  "org.tpolecat" %% "doobie-postgres"  % doobieVersion,
  "org.typelevel" %% "cats-core" % catsVersion,
)

lazy val bqWriterDependencies = Seq(
  "org.knowm.xchange" % "xchange-stream-coinbasepro" % xchangeVersion,
  "com.google.cloud" % "google-cloud-bigquery" % googleCloudBigqueryVersion exclude("com.google.guava", "guava"),
)

scalaVersion := "2.13.1"

parallelExecution in Test := false
test in assembly := {}

lazy val commonSettings = Seq(
  name := "coinbaseml",
  organization := "co.firstorderlabs",
  version := "0.1.0",
)

lazy val app = (project in file("."))
  .settings(commonSettings: _*)
  .enablePlugins(AssemblyPlugin)

assemblyMergeStrategy in assembly := {
    case "git.properties" => MergeStrategy.first
    case "META-INF/io.netty.versions.properties" => MergeStrategy.first
    case x =>
        val oldStrategy = (assemblyMergeStrategy in assembly).value
        oldStrategy(x)
}

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

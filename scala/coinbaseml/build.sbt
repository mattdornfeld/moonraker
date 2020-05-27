scalaVersion := "2.13.1"
name := "coinbaseml"
organization := "co.firstorderlabs"
version := "0.1.0"

//lazy val commonSettings = Seq(
//  organization := "co.firstorderlabs",
//  version := "0.1.0-SNAPSHOT"
//)
//
//lazy val app = (project in file(".")).
//  settings(commonSettings: _*).
//  settings(
//    name := "fat-jar-test"
//  ).
//  enablePlugins(AssemblyPlugin)

resolvers in Global ++= Seq(
  "Sbt plugins"                   at "https://dl.bintray.com/sbt/sbt-plugin-releases",
  "Maven Central Server"          at "http://repo1.maven.org/maven2",
  "TypeSafe Repository Releases"  at "http://repo.typesafe.com/typesafe/releases/",
  "TypeSafe Repository Snapshots" at "http://repo.typesafe.com/typesafe/snapshots/"
)

val doobieVersion = "0.8.8"
val scalaTestVersion = "3.1.1"

resolvers ++= Seq(
  Resolver.sonatypeRepo("releases"),
)

libraryDependencies += "org.scalactic" %% "scalactic" % scalaTestVersion
libraryDependencies += "org.scalatest" %% "scalatest" % scalaTestVersion % "test"
libraryDependencies += "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf"
libraryDependencies += "org.typelevel" %% "cats-core" % "2.0.0"
libraryDependencies += "com.typesafe.akka" %% "akka-actor-typed" % "2.6.4"

libraryDependencies ++= Seq(
  "org.tpolecat" %% "doobie-core" % doobieVersion,
  "org.tpolecat" %% "doobie-postgres"  % doobieVersion
)

libraryDependencies ++= Seq(
    "io.grpc" % "grpc-netty" % scalapb.compiler.Version.grpcJavaVersion,
    "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion
)

PB.targets in Compile := Seq(
  scalapb.gen() -> (sourceManaged in Compile).value
)

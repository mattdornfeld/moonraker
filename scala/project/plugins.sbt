addSbtPlugin("com.thesamet" % "sbt-protoc" % "0.99.33")
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.15.0")
addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.5.0")

libraryDependencies += "com.thesamet.scalapb" % "compilerplugin_2.12" % "0.10.9"
libraryDependencies += "org.scala-js" %% "scalajs-env-jsdom-nodejs" % "1.1.0"

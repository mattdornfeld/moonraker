scalaVersion := "2.13.1"
name := "coinbaseml"
organization := "co.firstorderlabs"
version := "0.1.0"

libraryDependencies += "org.typelevel" %% "cats-core" % "2.0.0"
libraryDependencies += "org.scalactic" %% "scalactic" % "3.1.1"
libraryDependencies += "org.scalatest" %% "scalatest" % "3.1.1" % "test"
libraryDependencies ++= Seq(
    "com.beachape" %% "enumeratum" % "1.5.15"
)

// Here, `libraryDependencies` is a set of dependencies, and by using `+=`,
// we're adding the cats dependency to the set of dependencies that sbt will go
// and fetch when it starts up.
// Now, in any Scala file, you can import classes, objects, etc., from cats with
// a regular import.

// TIP: To find the "dependency" that you need to add to the
// `libraryDependencies` set, which in the above example looks like this:

// "org.typelevel" %% "cats-core" % "2.0.0"

// You can use Scaladex, an index of all known published Scala libraries. There,
// after you find the library you want, you can just copy/paste the dependency
// information that you need into your build file. For example, on the
// typelevel/cats Scaladex page,
// https://index.scala-lang.org/typelevel/cats, you can copy/paste the sbt
// dependency from the sbt box on the right-hand side of the screen.

// IMPORTANT NOTE: while build files look _kind of_ like regular Scala, it's
// important to note that syntax in *.sbt files doesn't always behave like
// regular Scala. For example, notice in this build file that it's not required
// to put our settings into an enclosing object or class. Always remember that
// sbt is a bit different, semantically, than vanilla Scala.

// ============================================================================

// Most moderately interesting Scala projects don't make use of the very simple
// build file style (called "bare style") used in this build.sbt file. Most
// intermediate Scala projects make use of so-called "multi-project" builds. A
// multi-project build makes it possible to have different folders which sbt can
// be configured differently for. That is, you may wish to have different
// dependencies or different testing frameworks defined for different parts of
// your codebase. Multi-project builds make this possible.

// Here's a quick glimpse of what a multi-project build looks like for this
// build, with only one "subproject" defined, called `root`:

// lazy val root = (project in file(".")).
//   settings(
//     inThisBuild(List(
//       organization := "ch.epfl.scala",
//       scalaVersion := "2.13.1"
//     )),
//     name := "hello-world"
//   )

// To learn more about multi-project builds, head over to the official sbt
// documentation at http://www.scala-sbt.org/documentation.html

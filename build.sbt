import ReleaseTransformations._
import sbt._

lazy val akkaVersion = "2.5.13"
lazy val http4sOrg = "com.github.benhutchison"
lazy val http4sVersion = "1.0.0-SNAPSHOT-PR1923"
lazy val theScalaVersion = "2.12.6"

lazy val commonSettings = Seq(
  organization := "com.github.benhutchison",
  scalaVersion := theScalaVersion,
  scalacOptions ++= Seq("-feature", "-deprecation", "-language:implicitConversions", "-language:higherKinds"),
  resolvers += Resolver.sonatypeRepo("snapshots"),
)

lazy val root = (project in file("."))
  .settings(
    commonSettings,
    libraryDependencies ++= Seq(
      http4sOrg %% "http4s-core" % http4sVersion,
      http4sOrg %% "http4s-blaze-server" % http4sVersion,
      "org.http4s" %% "http4s-websocket" % "0.2.1",
      "com.github.benhutchison" %% "factor" % "0.1",
    ),
    name := "http4s-factor",
    crossScalaVersions := Seq(theScalaVersion),
    publishMavenStyle := true,
    licenses += ("The Apache Software License, Version 2.0", url("http://www.apache.org/licenses/LICENSE-2.0.txt")),
    homepage := Some(url("https://github.com/benhutchison/factor")),
    developers := List(Developer("benhutchison", "Ben Hutchison", "brhutchison@gmail.com", url = url("https://github.com/benhutchison"))),
    scmInfo := Some(ScmInfo(url("https://github.com/benhutchison/factor"), "scm:git:https://github.com/benhutchison/factor.git")),
    releaseCrossBuild := true,
    releasePublishArtifactsAction := PgpKeys.publishSigned.value,
    releaseProcess := Seq[ReleaseStep](
      checkSnapshotDependencies,
      inquireVersions,
      runClean,
      runTest,
      setReleaseVersion,
      commitReleaseVersion,
      tagRelease,
      publishArtifacts,
      setNextVersion,
      commitNextVersion,
    ),
  )

lazy val integrationTest = (project in file("integrationTest"))
  .enablePlugins(MultiJvmPlugin)
  .configs(MultiJvm)
  .dependsOn(root)
  .settings(
    commonSettings,
    libraryDependencies ++= Seq(
      http4sOrg %% "http4s-dsl" % http4sVersion,
      "com.typesafe.akka" %% "akka-http"   % "10.1.3",
      "com.typesafe.akka" %% "akka-stream" % akkaVersion,
      "org.typelevel" %% "mouse" % "0.17",
    ),
    name := "integrationTest",
    publish / skip := true,
  )

ThisBuild / publishTo := Some(
  if (isSnapshot.value)
    Opts.resolver.sonatypeSnapshots
  else
    Opts.resolver.sonatypeStaging
)


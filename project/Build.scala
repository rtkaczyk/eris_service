import sbt._

import Keys._
import AndroidKeys._

object General {
  val settings = Defaults.defaultSettings ++ Seq (
    name := "eris",
    version := "1.0.0",
    versionCode := 0,
    scalaVersion := "2.9.2",
    platformName in Android := "android-8"
  )

  val proguardSettings = Seq (
    useProguard in Android := true
  )

  lazy val fullAndroidSettings =
    General.settings ++
    AndroidProject.androidSettings ++
    proguardSettings ++
    AndroidManifestGenerator.settings ++
    Seq (
      libraryDependencies += "com.google.protobuf" % "protobuf-java" % "2.4.1",
      libraryDependencies += "rtkaczyk.eris" % "api" % "1.0.0"
    )
}

object AndroidBuild extends Build {
  lazy val main = Project (
    "eris",
    file("."),
    settings = General.fullAndroidSettings 
  )
}

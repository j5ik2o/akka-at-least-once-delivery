ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "2.13.14"

ThisBuild / scalaBinaryVersion := CrossVersion.binaryScalaVersion(scalaVersion.value)

val akkaVersion = "2.6.18"

lazy val root = (project in file("."))
  .settings(
    name := "akka-at-least-once-delivery",
    Compile / scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-Xlog-reflective-calls", "-Xlint"),
    Test / testOptions += Tests.Argument("-oDF"),
    Test / logBuffered := false,
    resolvers ++= Seq(
      "Akka Repository" at "https://repo.akka.io/maven/"
    ),
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-actor-typed"           % akkaVersion,
      "com.typesafe.akka" %% "akka-persistence-typed"     % akkaVersion,
      "com.typesafe.akka" %% "akka-serialization-jackson" % akkaVersion,
      "ch.qos.logback"     % "logback-classic"            % "1.2.9",
      "com.typesafe.akka" %% "akka-actor-testkit-typed"   % akkaVersion % Test,
      "org.scalatest"     %% "scalatest"                  % "3.1.0"     % Test
    ),
    semanticdbEnabled := true,
    semanticdbVersion := scalafixSemanticdb.revision
  ).settings(kotlinLib("stdlib"), kotlinVersion := "1.6.20")

// --- Custom commands
addCommandAlias("lint", ";scalafmtCheck;test:scalafmtCheck;scalafmtSbtCheck;scalafixAll --check")
addCommandAlias("fmt", ";scalafmtAll;scalafmtSbt;scalafix RemoveUnused")

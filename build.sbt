lazy val akkaHttpVersion = "10.0.10"
lazy val akkaVersion     = "2.5.4"

enablePlugins(JavaServerAppPackaging)

organization := "io.otoroshi"
name := """otoroshi-akka-http"""
version := "1.0.0-SNAPSHOT"

scalaVersion := "2.12.3"

mainClass in Compile := Some("io.otoroshi.Main")
mainClass in reStart := Some("io.otoroshi.Main")
mainClass in assembly := Some("io.otoroshi.Main")

assemblyJarName in assembly := "otoroshi.jar"
test in assembly := {}

resolvers += "bintray" at "http://jcenter.bintray.com"

libraryDependencies ++= Seq(
  "org.gnieh"              %% "diffson-play-json"    % "2.2.3" excludeAll (ExclusionRule(organization = "com.typesafe.akka")),
  "org.iq80.leveldb"       % "leveldb"               % "0.9",
  "com.typesafe.akka"      %% "akka-stream-kafka"    % "0.17",
  "com.github.etaty"       %% "rediscala"            % "1.8.0",
  "com.github.gphat"       %% "censorinus"           % "2.1.6",
  "com.datastax.cassandra" % "cassandra-driver-core" % "3.3.0" classifier "shaded" excludeAll (
    ExclusionRule(organization = "io.netty"),
    ExclusionRule(organization = "com.typesafe.akka")
  ),
  "org.iq80.leveldb"         % "leveldb"                   % "0.9",
  "com.softwaremill.macwire" %% "macros"                   % "2.3.0",
  "com.typesafe.play"        %% "play-json"                % "2.6.7",
  "com.typesafe.play"        %% "play-json-joda"           % "2.6.7",
  "ch.qos.logback"           % "logback-classic"           % "1.1.8",
  "io.dropwizard.metrics"    % "metrics-core"              % "3.1.2",
  "com.auth0"                % "java-jwt"                  % "3.1.0",
  "com.yubico"               % "u2flib-server-core"        % "0.16.0",
  "com.yubico"               % "u2flib-server-attestation" % "0.16.0",
  "de.svenkubiak"            % "jBCrypt"                   % "0.4.1",
  "com.typesafe.akka"        %% "akka-http"                % akkaHttpVersion,
  "com.typesafe.akka"        %% "akka-stream"              % akkaVersion,
  "org.scalatest"            %% "scalatest"                % "3.0.1" % Test
)

scalacOptions ++= Seq(
  "-feature",
  "-language:higherKinds",
  "-language:implicitConversions",
  "-language:existentials",
  "-language:postfixOps"
)

sources in (Compile, doc) := Seq.empty
publishArtifact in (Compile, packageDoc) := false

scalafmtVersion in ThisBuild := "1.2.0"

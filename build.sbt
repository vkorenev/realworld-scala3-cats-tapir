val isRunningInCi = sys.env.get("CI").contains("true")

ThisBuild / tpolecatDefaultOptionsMode := {
  if (isRunningInCi)
    org.typelevel.sbt.tpolecat.CiMode
  else
    org.typelevel.sbt.tpolecat.DevMode
}
ThisBuild / organization := "com.example.realworld"
ThisBuild / organizationName := "Example"
ThisBuild / version := sys.env.getOrElse("PROJECT_VERSION", "0.0.0-LOCAL-SNAPSHOT")
ThisBuild / scalaVersion := "3.7.3"
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision

val catsVersion = "2.13.0"
val catsEffectVersion = "3.6.3"
val fs2Version = "3.12.2"
val http4sVersion = "0.23.32"
val log4j2Version = "2.25.2"
val munitVersion = "1.2.0"
val munitCatsEffectVersion = "2.1.0"
val tapirVersion = "1.11.48"
val jsoniterScalaVersion = "2.38.3"
val doobieVersion = "1.0.0-RC10"
val h2Version = "2.4.240"

lazy val log4j2Bom = com.here.bom.Bom(
  "org.apache.logging.log4j" % "log4j-bom" % log4j2Version
)

val jvmOptions = List(
  "-Djava.util.logging.manager=org.apache.logging.log4j.jul.LogManager",
)

lazy val root = (project in file("."))
  .settings(
    log4j2Bom,
  )
  .settings(
    name := "realworld-backend",
    libraryDependencies ++= Seq(
      "co.fs2" %% "fs2-core" % fs2Version,
      "co.fs2" %% "fs2-io" % fs2Version,
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % jsoniterScalaVersion,
      "com.h2database" % "h2" % h2Version % Runtime,
      "com.softwaremill.sttp.tapir" %% "tapir-core" % tapirVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-http4s-server" % tapirVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-jsoniter-scala" % tapirVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-swagger-ui-bundle" % tapirVersion,
      "org.apache.logging.log4j" % "log4j-core" % log4j2Bom.key.value % Runtime,
      "org.apache.logging.log4j" % "log4j-jul" % log4j2Bom.key.value % Runtime,
      "org.apache.logging.log4j" % "log4j-slf4j2-impl" % log4j2Bom.key.value % Runtime,
      "org.http4s" %% "http4s-ember-server" % http4sVersion,
      "org.scalameta" %% "munit" % munitVersion % Test,
      "org.tpolecat" %% "doobie-core" % doobieVersion,
      "org.tpolecat" %% "doobie-h2" % doobieVersion,
      "org.tpolecat" %% "doobie-hikari" % doobieVersion,
      "org.typelevel" %% "cats-core" % catsVersion,
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "org.typelevel" %% "munit-cats-effect" % munitCatsEffectVersion % Test,
    ),
    dependencyOverrides ++= log4j2Bom.key.value.bomDependencies,
    Compile / run / fork := true,
    javaOptions ++= jvmOptions,
    testFrameworks += new TestFramework("munit.Framework"),
    scalacOptions ++= Seq(
      "-Wsafe-init",
      "-Wunused:all",
    ),
  )

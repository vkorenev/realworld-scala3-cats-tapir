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
val http4sVersion = "0.23.33"
val log4j2Version = "2.25.2"
val munitVersion = "1.2.1"
val munitCatsEffectVersion = "2.1.0"
val testcontainersScalaVersion = "0.43.6"
val tapirVersion = "1.12.1"
val jsoniterScalaVersion = "2.38.3"
val doobieVersion = "1.0.0-RC10"
val postgresVersion = "42.7.8"
val jwtScalaVersion = "11.0.3"
val pureconfigVersion = "0.17.9"

lazy val log4j2Bom = com.here.bom.Bom(
  "org.apache.logging.log4j" % "log4j-bom" % log4j2Version
)

val jvmOptions = List(
  "-Djava.util.logging.manager=org.apache.logging.log4j.jul.LogManager"
)

lazy val app = (project in file("app"))
  .settings(
    log4j2Bom
  )
  .settings(
    libraryDependencies ++= Seq(
      "co.fs2" %% "fs2-core" % fs2Version,
      "co.fs2" %% "fs2-io" % fs2Version,
      "com.github.jwt-scala" %% "jwt-core" % jwtScalaVersion,
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core" % jsoniterScalaVersion,
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % jsoniterScalaVersion,
      "com.github.pureconfig" %% "pureconfig-cats-effect" % pureconfigVersion,
      "com.github.pureconfig" %% "pureconfig-core" % pureconfigVersion,
      "com.github.pureconfig" %% "pureconfig-generic-scala3" % pureconfigVersion,
      "org.postgresql" % "postgresql" % postgresVersion % Runtime,
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
      "org.tpolecat" %% "doobie-postgres" % doobieVersion,
      "org.tpolecat" %% "doobie-hikari" % doobieVersion,
      "org.tpolecat" %% "doobie-munit" % doobieVersion % Test,
      "org.typelevel" %% "cats-core" % catsVersion,
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "org.typelevel" %% "munit-cats-effect" % munitCatsEffectVersion % Test
    ),
    dependencyOverrides ++= log4j2Bom.key.value.bomDependencies,
    Compile / run / fork := true,
    Test / fork := true,
    javaOptions ++= jvmOptions,
    testFrameworks += new TestFramework("munit.Framework"),
    scalacOptions ++= Seq(
      "-Wsafe-init",
      "-Wunused:all",
      "-source:3.7-migration",
      "-Xmax-inlines:64"
    )
  )

lazy val integration = (project in file("integration"))
  .dependsOn(app % "compile->compile;test->test")
  .settings(
    log4j2Bom
  )
  .settings(
    publish / skip := true,
    libraryDependencies ++= Seq(
      "com.dimafeng" %% "testcontainers-scala-munit" % testcontainersScalaVersion % Test,
      "com.dimafeng" %% "testcontainers-scala-postgresql" % testcontainersScalaVersion % Test
    ),
    dependencyOverrides ++= log4j2Bom.key.value.bomDependencies,
    Compile / run / fork := true,
    Test / fork := true,
    javaOptions ++= jvmOptions,
    testFrameworks += new TestFramework("munit.Framework"),
    scalacOptions ++= Seq(
      "-Wsafe-init",
      "-Wunused:all",
      "-source:3.7-migration",
      "-Xmax-inlines:64"
    )
  )

lazy val root = (project in file("."))
  .aggregate(app, integration)
  .settings(
    name := "realworld-conduit-backend"
  )

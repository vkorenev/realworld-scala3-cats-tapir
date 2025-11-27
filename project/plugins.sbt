addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.4")
addSbtPlugin("com.here.platform" % "sbt-bom" % "1.0.31")
addSbtPlugin("de.gccc.sbt" % "sbt-jib" % "1.4.2")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.6")
addSbtPlugin("org.typelevel" % "sbt-tpolecat" % "0.5.2")

libraryDependencies += "com.google.cloud.tools" % "jib-core" % "0.28.1"
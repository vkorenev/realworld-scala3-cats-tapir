package com.example.realworld

import cats.effect.Async
import cats.effect.Resource
import com.comcast.ip4s.*
import fs2.io.net.Network
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Router
import org.http4s.server.Server as Http4sServer

case class HttpServer[F[_]: {Async, Network}](endpoints: Endpoints[F]):
  val httpApp = Router("/" -> endpoints.routes).orNotFound

  def resource: Resource[F, Http4sServer] =
    EmberServerBuilder
      .default[F]
      .withHost(ipv4"0.0.0.0")
      .withPort(port"8080")
      .withHttpApp(httpApp)
      .build

package com.example.realworld

import cats.effect.Async
import org.http4s.HttpRoutes
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.server.http4s.Http4sServerInterpreter
import sttp.tapir.server.http4s.Http4sServerOptions
import sttp.tapir.server.interceptor.cors.CORSConfig
import sttp.tapir.server.interceptor.cors.CORSInterceptor
import sttp.tapir.swagger.bundle.SwaggerInterpreter

case class Endpoints[F[_]: Async]():
  private def livenessEndpoint = endpoint.get
    .in("__health" / "liveness")
    .out(statusCode(StatusCode.NoContent))
    .description("Liveness probe")
    .serverLogicPure[F](_ => Right(()))

  def routes: HttpRoutes[F] =
    val serverEndpoints = List(
      livenessEndpoint
    )

    val swaggerEndpoints =
      SwaggerInterpreter().fromServerEndpoints[F](serverEndpoints, "RealWorld API", "1.0.0")

    val serverOptions = Http4sServerOptions.customiseInterceptors
      .corsInterceptor(CORSInterceptor.customOrThrow(CORSConfig.default.allowAllMethods))
      .options

    Http4sServerInterpreter[F](serverOptions).toRoutes(serverEndpoints ++ swaggerEndpoints)

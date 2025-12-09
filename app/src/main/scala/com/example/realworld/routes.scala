package com.example.realworld

import cats.effect.Async
import org.http4s.HttpRoutes
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import sttp.tapir.server.http4s.Http4sServerInterpreter
import sttp.tapir.server.http4s.Http4sServerOptions
import sttp.tapir.server.interceptor.cors.CORSConfig
import sttp.tapir.server.interceptor.cors.CORSInterceptor
import sttp.tapir.server.metrics.otel4s.Otel4sMetrics
import sttp.tapir.server.tracing.otel4s.Otel4sTracing

def routes[F[_]: {Async, Meter, Tracer}](endpoints: Endpoints[F]): HttpRoutes[F] =
  val serverOptions = Http4sServerOptions.customiseInterceptors
    .corsInterceptor(CORSInterceptor.customOrThrow(CORSConfig.default.allowAllMethods))
    .metricsInterceptor(Otel4sMetrics.default(Meter[F]).metricsInterceptor())
    .prependInterceptor(Otel4sTracing(Tracer[F]))
    .options

  Http4sServerInterpreter[F](serverOptions).toRoutes(endpoints.allEndpoints)

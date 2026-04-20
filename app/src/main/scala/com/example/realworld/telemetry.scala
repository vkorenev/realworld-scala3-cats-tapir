package com.example.realworld

import cats.effect.Resource
import cats.effect.Sync
import cats.syntax.functor.*
import io.opentelemetry.instrumentation.hikaricp.v3_0.HikariTelemetry
import io.opentelemetry.instrumentation.log4j.appender.v2_17.OpenTelemetryAppender
import io.opentelemetry.instrumentation.runtimetelemetry.RuntimeTelemetry
import org.typelevel.otel4s.oteljava.OtelJava

def registerRuntimeMetrics[F[_]: Sync](otel4s: OtelJava[F]): Resource[F, Unit] =
  Resource
    .fromAutoCloseable(Sync[F].delay {
      RuntimeTelemetry.create(otel4s.underlying)
    })
    .void

def hikariMetricsTrackerFactory(otel4s: OtelJava[?]) =
  HikariTelemetry
    .create(otel4s.underlying)
    .createMetricsTrackerFactory()

def registerOpenTelemetryAppender[F[_]: Sync](otel4s: OtelJava[F]): F[Unit] =
  Sync[F].delay {
    OpenTelemetryAppender.install(otel4s.underlying)
  }

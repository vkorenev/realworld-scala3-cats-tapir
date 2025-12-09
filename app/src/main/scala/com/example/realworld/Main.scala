package com.example.realworld

import cats.effect.IO
import cats.effect.IOApp
import cats.effect.Resource
import com.example.realworld.auth.JwtAuthToken
import com.example.realworld.config.AppConfig
import com.example.realworld.db.Database
import com.example.realworld.security.Pbkdf2PasswordHasher
import com.example.realworld.service.ArticleService
import com.example.realworld.service.CommentService
import com.example.realworld.service.UserService
import doobie.Transactor
import doobie.hikari.HikariTransactor
import doobie.otel4s.tracing.TraceTransactor
import org.typelevel.otel4s.context.LocalProvider
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.oteljava.OtelJava
import org.typelevel.otel4s.oteljava.context.Context
import org.typelevel.otel4s.oteljava.context.IOLocalContextStorage
import org.typelevel.otel4s.trace.Tracer

import javax.sql.DataSource

object Main extends IOApp.Simple:
  private def traceTransactor(
      hikariTransactor: HikariTransactor[IO],
      otel4s: OtelJava[IO]
  ): Transactor.Aux[IO, DataSource] =
    TraceTransactor.fromDataSource[IO](
      otel4s.underlying,
      transactor = hikariTransactor.asInstanceOf[Transactor.Aux[IO, DataSource]],
      transactionInstrumenterEnabled = true
    )

  private val telemetryScopeName = "com.example.realworld.backend"

  override def run: IO[Unit] =
    given LocalProvider[IO, Context] = IOLocalContextStorage.localProvider[IO]
    val resources =
      for
        otel4s <- OtelJava.autoConfigured[IO]()
        given Meter[IO] <- Resource.eval(otel4s.meterProvider.get(telemetryScopeName))
        given Tracer[IO] <- Resource.eval(otel4s.tracerProvider.get(telemetryScopeName))
        _ <- Resource.eval(registerOpenTelemetryAppender[IO](otel4s))
        _ <- registerRuntimeMetrics[IO](otel4s)
        appConfig <- Resource.eval(AppConfig.load[IO])
        xa <- HikariTransactor.fromConfig[IO](
          appConfig.database,
          metricsTrackerFactory = Some(hikariMetricsTrackerFactory(otel4s))
        )
        txa = traceTransactor(xa, otel4s)
        _ <- Resource.eval(Database.initialize[IO](txa))
        authToken = JwtAuthToken[IO](appConfig.jwt.secretKey)
        passwordHasher = Pbkdf2PasswordHasher[IO]()
        userService = UserService.live[IO](txa, passwordHasher, authToken)
        articleService = ArticleService.live[IO](txa)
        commentService = CommentService.live[IO](txa)
        endpoints = Endpoints[IO](userService, articleService, commentService, authToken)
        server <- HttpServer(routes(endpoints)).resource
      yield server

    resources.useForever

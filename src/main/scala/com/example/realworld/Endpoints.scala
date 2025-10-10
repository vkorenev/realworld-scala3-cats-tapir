package com.example.realworld

import cats.effect.Async
import com.example.realworld.model.NewUserRequest
import com.example.realworld.model.User
import com.example.realworld.model.UserResponse
import org.http4s.HttpRoutes
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.json.jsoniter.*
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

  private def registerUserEndpoint = endpoint.post
    .in("api" / "users")
    .in(jsonBody[NewUserRequest])
    .out(jsonBody[UserResponse])
    .description("Register a new user")
    .tag("User and Authentication")
    .serverLogicSuccess[F] { request =>
      val registered = User(
        email = request.user.email,
        token = "jwt.token.here",
        username = request.user.username,
        bio = None,
        image = None
      )
      Async[F].pure(UserResponse(registered))
    }

  def routes: HttpRoutes[F] =
    val serverEndpoints = List(
      livenessEndpoint,
      registerUserEndpoint
    )

    val swaggerEndpoints =
      SwaggerInterpreter().fromServerEndpoints[F](serverEndpoints, "RealWorld API", "1.0.0")

    val serverOptions = Http4sServerOptions.customiseInterceptors
      .corsInterceptor(CORSInterceptor.customOrThrow(CORSConfig.default.allowAllMethods))
      .options

    Http4sServerInterpreter[F](serverOptions).toRoutes(serverEndpoints ++ swaggerEndpoints)

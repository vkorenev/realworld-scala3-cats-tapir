package com.example.realworld

import cats.effect.Async
import cats.syntax.functor.*
import com.example.realworld.model.LoginUserRequest
import com.example.realworld.model.NewUserRequest
import com.example.realworld.model.UserResponse
import com.example.realworld.repository.UserRepository
import org.http4s.HttpRoutes
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.json.jsoniter.*
import sttp.tapir.server.http4s.Http4sServerInterpreter
import sttp.tapir.server.http4s.Http4sServerOptions
import sttp.tapir.server.interceptor.cors.CORSConfig
import sttp.tapir.server.interceptor.cors.CORSInterceptor
import sttp.tapir.swagger.bundle.SwaggerInterpreter

case class Endpoints[F[_]: Async](userRepository: UserRepository[F]):
  private def livenessEndpoint = endpoint.get
    .in("__health" / "liveness")
    .out(statusCode(StatusCode.NoContent))
    .description("Liveness probe")
    .serverLogicPure[F](_ => Right(()))

  private def loginUserEndpoint = endpoint.post
    .in("api" / "users" / "login")
    .in(jsonBody[LoginUserRequest])
    .out(jsonBody[UserResponse])
    .errorOut(statusCode(StatusCode.Unauthorized))
    .description("Authenticate an existing user")
    .tag("User and Authentication")
    .serverLogic[F] { request =>
      userRepository.authenticate(request.user.email, request.user.password).map {
        case Some(user) => Right(UserResponse(user))
        case None => Left(())
      }
    }

  private def registerUserEndpoint = endpoint.post
    .in("api" / "users")
    .in(jsonBody[NewUserRequest])
    .out(jsonBody[UserResponse])
    .description("Register a new user")
    .tag("User and Authentication")
    .serverLogicSuccess[F] { request =>
      userRepository
        .create(request.user)
        .map(UserResponse.apply)
    }

  def routes: HttpRoutes[F] =
    val serverEndpoints = List(
      livenessEndpoint,
      loginUserEndpoint,
      registerUserEndpoint
    )

    val swaggerEndpoints =
      SwaggerInterpreter().fromServerEndpoints[F](serverEndpoints, "RealWorld API", "1.0.0")

    val serverOptions = Http4sServerOptions.customiseInterceptors
      .corsInterceptor(CORSInterceptor.customOrThrow(CORSConfig.default.allowAllMethods))
      .options

    Http4sServerInterpreter[F](serverOptions).toRoutes(serverEndpoints ++ swaggerEndpoints)

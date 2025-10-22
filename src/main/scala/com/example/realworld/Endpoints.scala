package com.example.realworld

import cats.ApplicativeThrow
import cats.effect.Async
import cats.syntax.all.*
import com.example.realworld.auth.AuthToken
import com.example.realworld.model.LoginUserRequest
import com.example.realworld.model.NewUserRequest
import com.example.realworld.model.ProfileResponse
import com.example.realworld.model.UpdateUserRequest
import com.example.realworld.model.UserId
import com.example.realworld.model.UserResponse
import com.example.realworld.service.UserService
import org.http4s.HttpRoutes
import sttp.model.StatusCode
import sttp.model.headers.WWWAuthenticateChallenge
import sttp.tapir.*
import sttp.tapir.json.jsoniter.*
import sttp.tapir.server.http4s.Http4sServerInterpreter
import sttp.tapir.server.http4s.Http4sServerOptions
import sttp.tapir.server.interceptor.cors.CORSConfig
import sttp.tapir.server.interceptor.cors.CORSInterceptor
import sttp.tapir.swagger.bundle.SwaggerInterpreter

case class Endpoints[F[_]: Async](userService: UserService[F], authToken: AuthToken[F]):
  /** API specification uses non-standard authentication scheme */
  private val TokenAuthScheme = "Token"

  private def tokenAuth[T: Codec[List[String], *, CodecFormat.TextPlain]](
      challenge: WWWAuthenticateChallenge = WWWAuthenticateChallenge(TokenAuthScheme)
  ): EndpointInput.Auth[T, EndpointInput.AuthType.Http] =
    TapirAuth.http(TokenAuthScheme, challenge)

  private val secureEndpoint = endpoint
    .securityIn(
      auth
        .bearer[Option[String]]()
        .and(tokenAuth[Option[String]]())
        .mapDecode {
          case (Some(token), _) => DecodeResult.Value(token)
          case (_, Some(token)) => DecodeResult.Value(token)
          case (None, None) => DecodeResult.Missing
        } { token =>
          (Some(token), None)
        }
    )
    .errorOut(statusCode(StatusCode.Unauthorized).and(jsonBody[Unauthorized]))
    .serverSecurityLogicRecoverErrors[UserId, F](authToken.resolve)

  private val optionallySecureEndpoint = endpoint
    .securityIn(
      auth
        .bearer[Option[String]]()
        .and(tokenAuth[Option[String]]())
        .and(emptyAuth)
        .mapDecode {
          case (Some(token), _) => DecodeResult.Value(Some(token))
          case (_, Some(token)) => DecodeResult.Value(Some(token))
          case (None, None) => DecodeResult.Value(None)
        } {
          case Some(token) => (Some(token), None)
          case None => (None, None)
        }
    )
    .errorOut(statusCode(StatusCode.Unauthorized).and(jsonBody[Unauthorized]))
    .serverSecurityLogicRecoverErrors[Option[UserId], F](_.traverse(authToken.resolve))

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
    .description("Login for existing user")
    .tag("User and Authentication")
    .serverLogic[F] { request =>
      userService.authenticate(request.user.email, request.user.password).map {
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
      userService
        .register(request.user)
        .map(UserResponse.apply)
    }

  private def currentUserEndpoint = secureEndpoint.get
    .in("api" / "user")
    .out(jsonBody[UserResponse])
    .description("Gets the currently logged-in user")
    .tag("User and Authentication")
    .serverLogicRecoverErrors { userId => _ =>
      userService.findById(userId).map(UserResponse.apply)
    }

  private def updateUserEndpoint = secureEndpoint.put
    .in("api" / "user")
    .in(jsonBody[UpdateUserRequest])
    .out(jsonBody[UserResponse])
    .description("Updated user information for current user")
    .tag("User and Authentication")
    .serverLogicRecoverErrors { userId => request =>
      userService.update(userId, request.user).map(UserResponse.apply)
    }

  private def profileEndpoint = optionallySecureEndpoint.get
    .in("api" / "profiles" / path[String]("username"))
    .out(jsonBody[ProfileResponse])
    .description("Get a profile of a user of the system. Auth is optional")
    .tag("Profile")
    .serverLogicRecoverErrors { userId => username =>
      userService
        .findProfile(userId, username)
        .flatMap(ApplicativeThrow[F].fromOption(_, NotFound()).map(ProfileResponse.apply))
    }

  def routes: HttpRoutes[F] =
    val serverEndpoints = List(
      livenessEndpoint,
      loginUserEndpoint,
      registerUserEndpoint,
      currentUserEndpoint,
      updateUserEndpoint,
      profileEndpoint
    )

    val swaggerEndpoints =
      SwaggerInterpreter().fromServerEndpoints[F](serverEndpoints, "RealWorld Conduit API", "1.0.0")

    val serverOptions = Http4sServerOptions.customiseInterceptors
      .corsInterceptor(CORSInterceptor.customOrThrow(CORSConfig.default.allowAllMethods))
      .options

    Http4sServerInterpreter[F](serverOptions).toRoutes(serverEndpoints ++ swaggerEndpoints)

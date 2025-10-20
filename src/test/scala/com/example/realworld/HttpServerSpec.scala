package com.example.realworld

import cats.effect.IO
import cats.effect.Resource
import com.example.realworld.auth.AuthToken
import com.example.realworld.db.Database
import com.example.realworld.db.TestDatabaseConfig
import com.example.realworld.model.User
import com.example.realworld.model.UserResponse
import com.example.realworld.repository.DoobieUserRepository
import com.example.realworld.service.UserService
import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import fs2.Stream
import munit.CatsEffectSuite
import org.http4s.AuthScheme
import org.http4s.Credentials
import org.http4s.Headers
import org.http4s.MediaType
import org.http4s.Method
import org.http4s.Request
import org.http4s.Status
import org.http4s.headers.Authorization
import org.http4s.headers.`Content-Type`
import org.http4s.implicits.*

import java.nio.charset.StandardCharsets
import java.util.UUID

class HttpServerSpec extends CatsEffectSuite:
  private val httpAppFixture = ResourceSuiteLocalFixture(
    "http-app",
    for
      dbName <- Resource.eval(IO(s"http-app-${UUID.randomUUID().toString.replace("-", "")}"))
      transactor <- Database.transactor[IO](TestDatabaseConfig.forTest(dbName))
      _ <- Resource.eval(Database.initialize[IO](transactor))
      userRepository = DoobieUserRepository[IO](transactor)
      userService = UserService.live[IO](userRepository)
    yield Endpoints[IO](userService).routes.orNotFound
  )

  override def munitFixtures = List(httpAppFixture)

  test("liveness endpoint returns no content"):
    val httpApp = httpAppFixture()
    val request = Request[IO](Method.GET, uri"/__health/liveness")
    httpApp
      .run(request)
      .map(response => assertEquals(response.status, Status.NoContent))

  test("register user endpoint returns created user payload"):
    val httpApp = httpAppFixture()
    val payload =
      """{"user":{"username":"Jacob","email":"jake@jake.jake","password":"jakejake"}}"""
    val request = Request[IO](
      Method.POST,
      uri"/api/users",
      headers = Headers(`Content-Type`(MediaType.application.json)),
      body = Stream.emits(payload.getBytes(StandardCharsets.UTF_8)).covary[IO]
    )

    httpApp
      .run(request)
      .flatMap { response =>
        assertEquals(response.status, Status.Ok)
        response.as[String].map { body =>
          val decoded = readFromString[UserResponse](body)
          val expected = UserResponse(
            User(
              email = "jake@jake.jake",
              token = AuthToken.issue("jake@jake.jake"),
              username = "Jacob",
              bio = None,
              image = None
            )
          )

          assertEquals(decoded, expected)
        }
      }

  test("login user endpoint returns existing user payload"):
    val httpApp = httpAppFixture()
    val registerPayload =
      """{"user":{"username":"Alice","email":"alice@example.com","password":"wonderland"}}"""
    val loginPayload =
      """{"user":{"email":"alice@example.com","password":"wonderland"}}"""

    val registerRequest = Request[IO](
      Method.POST,
      uri"/api/users",
      headers = Headers(`Content-Type`(MediaType.application.json)),
      body = Stream.emits(registerPayload.getBytes(StandardCharsets.UTF_8)).covary[IO]
    )

    val loginRequest = Request[IO](
      Method.POST,
      uri"/api/users/login",
      headers = Headers(`Content-Type`(MediaType.application.json)),
      body = Stream.emits(loginPayload.getBytes(StandardCharsets.UTF_8)).covary[IO]
    )

    val expectedUser = User(
      email = "alice@example.com",
      token = AuthToken.issue("alice@example.com"),
      username = "Alice",
      bio = None,
      image = None
    )

    httpApp
      .run(registerRequest)
      .flatMap { response =>
        assertEquals(response.status, Status.Ok)
        response.as[String].map(_ => ())
      } *> httpApp
      .run(loginRequest)
      .flatMap { response =>
        assertEquals(response.status, Status.Ok)
        response.as[String].map { body =>
          val decoded = readFromString[UserResponse](body)
          val expected = UserResponse(expectedUser)
          assertEquals(decoded, expected)
        }
      }

  test("current user endpoint returns authenticated user payload"):
    val httpApp = httpAppFixture()
    val registerPayload =
      """{"user":{"username":"Carol","email":"carol@example.com","password":"secret"}}"""
    val token = AuthToken.issue("carol@example.com")

    val registerRequest = Request[IO](
      Method.POST,
      uri"/api/users",
      headers = Headers(`Content-Type`(MediaType.application.json)),
      body = Stream.emits(registerPayload.getBytes(StandardCharsets.UTF_8)).covary[IO]
    )

    val currentUserRequest = Request[IO](
      Method.GET,
      uri"/api/user",
      headers = Headers(Authorization(Credentials.Token(AuthScheme.Bearer, token)))
    )

    val expectedUser = User(
      email = "carol@example.com",
      token = token,
      username = "Carol",
      bio = None,
      image = None
    )

    httpApp
      .run(registerRequest)
      .flatMap { response =>
        assertEquals(response.status, Status.Ok)
        response.as[String].map(_ => ())
      } *> httpApp
      .run(currentUserRequest)
      .flatMap { response =>
        assertEquals(response.status, Status.Ok)
        response.as[String].map { body =>
          val decoded = readFromString[UserResponse](body)
          val expected = UserResponse(expectedUser)
          assertEquals(decoded, expected)
        }
      }

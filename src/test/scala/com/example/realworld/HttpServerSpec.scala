package com.example.realworld

import cats.effect.IO
import cats.effect.Resource
import com.example.realworld.db.Database
import com.example.realworld.db.TestDatabaseConfig
import com.example.realworld.model.User
import com.example.realworld.model.UserResponse
import com.example.realworld.repository.DoobieUserRepository
import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import fs2.Stream
import munit.CatsEffectSuite
import org.http4s.Headers
import org.http4s.MediaType
import org.http4s.Method
import org.http4s.Request
import org.http4s.Status
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
    yield Endpoints[IO](DoobieUserRepository[IO](transactor)).routes.orNotFound
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
              token = "jwt.token.here",
              username = "Jacob",
              bio = None,
              image = None
            )
          )

          assertEquals(decoded, expected)
        }
      }

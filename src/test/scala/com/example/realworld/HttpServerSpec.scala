package com.example.realworld

import cats.effect.IO
import com.example.realworld.model.User
import com.example.realworld.model.UserResponse
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

class HttpServerSpec extends CatsEffectSuite:
  test("liveness endpoint returns no content"):
    val request = Request[IO](Method.GET, uri"/__health/liveness")

    HttpServer(Endpoints[IO]()).httpApp
      .run(request)
      .map(response => assertEquals(response.status, Status.NoContent))

  test("register user endpoint returns created user payload"):
    val payload =
      """{"user":{"username":"Jacob","email":"jake@jake.jake","password":"jakejake"}}"""
    val request = Request[IO](
      Method.POST,
      uri"/api/users",
      headers = Headers(`Content-Type`(MediaType.application.json)),
      body = Stream.emits(payload.getBytes(StandardCharsets.UTF_8)).covary[IO]
    )

    HttpServer(Endpoints[IO]()).httpApp
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

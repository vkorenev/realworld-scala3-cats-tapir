package com.example.realworld

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.Method
import org.http4s.Request
import org.http4s.Status
import org.http4s.implicits.*

class HttpServerSpec extends CatsEffectSuite:
  test("liveness endpoint returns no content"):
    val request = Request[IO](Method.GET, uri"/__health/liveness")

    HttpServer(Endpoints[IO]()).httpApp
      .run(request)
      .map(response => assertEquals(response.status, Status.NoContent))

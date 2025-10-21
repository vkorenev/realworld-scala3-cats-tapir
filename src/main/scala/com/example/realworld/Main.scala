package com.example.realworld

import cats.effect.IO
import cats.effect.IOApp
import com.example.realworld.auth.JwtAuthToken
import com.example.realworld.db.Database
import com.example.realworld.repository.DoobieUserRepository
import com.example.realworld.service.UserService

object Main extends IOApp.Simple:
  override def run: IO[Unit] =
    val resources =
      for
        xa <- Database.transactor[IO](Database.default)
        _ <- cats.effect.Resource.eval(Database.initialize[IO](xa))
        authToken = JwtAuthToken[IO]()
        userRepository = DoobieUserRepository[IO](xa)
        userService = UserService.live[IO](userRepository, authToken)
        server <- HttpServer(Endpoints[IO](userService, authToken)).resource
      yield server

    resources.useForever

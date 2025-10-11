package com.example.realworld

import cats.effect.IO
import cats.effect.IOApp
import com.example.realworld.db.Database
import com.example.realworld.repository.DoobieUserRepository

object Main extends IOApp.Simple:
  override def run: IO[Unit] =
    val resources =
      for
        xa <- Database.transactor[IO](Database.default)
        _ <- cats.effect.Resource.eval(Database.initialize[IO](xa))
        server <- HttpServer(Endpoints[IO](DoobieUserRepository[IO](xa))).resource
      yield server

    resources.useForever

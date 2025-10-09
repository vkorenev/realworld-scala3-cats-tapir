package com.example.realworld

import cats.effect.IO
import cats.effect.IOApp

object Main extends IOApp.Simple:
  override def run: IO[Unit] =
    HttpServer(Endpoints[IO]()).resource.useForever

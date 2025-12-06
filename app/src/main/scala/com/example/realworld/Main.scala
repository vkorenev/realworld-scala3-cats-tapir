package com.example.realworld

import cats.effect.IO
import cats.effect.IOApp
import com.example.realworld.auth.JwtAuthToken
import com.example.realworld.config.AppConfig
import com.example.realworld.db.Database
import com.example.realworld.security.Pbkdf2PasswordHasher
import com.example.realworld.service.ArticleService
import com.example.realworld.service.CommentService
import com.example.realworld.service.UserService

object Main extends IOApp.Simple:
  override def run: IO[Unit] =
    val resources =
      for
        appConfig <- cats.effect.Resource.eval(AppConfig.load[IO])
        xa <- Database.transactor[IO](appConfig.database)
        _ <- cats.effect.Resource.eval(Database.initialize[IO](xa))
        authToken = JwtAuthToken[IO](appConfig.jwt.secretKey)
        passwordHasher = Pbkdf2PasswordHasher[IO]()
        userService = UserService.live[IO](xa, passwordHasher, authToken)
        articleService = ArticleService.live[IO](xa)
        commentService = CommentService.live[IO](xa)
        server <- HttpServer(
          Endpoints[IO](userService, articleService, commentService, authToken)
        ).resource
      yield server

    resources.useForever

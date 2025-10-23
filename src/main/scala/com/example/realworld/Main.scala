package com.example.realworld

import cats.effect.IO
import cats.effect.IOApp
import com.example.realworld.auth.JwtAuthToken
import com.example.realworld.config.AppConfig
import com.example.realworld.db.Database
import com.example.realworld.repository.DoobieArticleRepository
import com.example.realworld.repository.DoobieUserRepository
import com.example.realworld.security.Pbkdf2PasswordHasher
import com.example.realworld.service.ArticleService
import com.example.realworld.service.UserService

object Main extends IOApp.Simple:
  override def run: IO[Unit] =
    val resources =
      for
        appConfig <- cats.effect.Resource.eval(AppConfig.load[IO])
        xa <- Database.transactor[IO](appConfig.database)
        _ <- cats.effect.Resource.eval(Database.initialize[IO](xa))
        authToken = JwtAuthToken[IO](appConfig.jwt.secretKey)
        userRepository = DoobieUserRepository[IO](xa, Pbkdf2PasswordHasher[IO]())
        articleRepository = DoobieArticleRepository[IO](xa)
        userService = UserService.live[IO](userRepository, authToken)
        articleService = ArticleService.live[IO](articleRepository)
        server <- HttpServer(Endpoints[IO](userService, articleService, authToken)).resource
      yield server

    resources.useForever

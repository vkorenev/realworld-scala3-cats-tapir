package com.example.realworld.repository

import cats.effect.Async
import cats.syntax.functor.*
import com.example.realworld.model.NewUser
import com.example.realworld.model.User
import doobie.implicits.*
import doobie.util.transactor.Transactor

trait UserRepository[F[_]]:
  def create(input: NewUser): F[User]
  def authenticate(email: String, password: String): F[Option[User]]

final class DoobieUserRepository[F[_]: Async](xa: Transactor[F]) extends UserRepository[F]:
  private def insert(input: NewUser) =
    sql"""
      INSERT INTO users (username, email, password, bio, image)
      VALUES (${input.username}, ${input.email}, ${input.password}, NULL, NULL)
    """.update.run.transact(xa)

  private def selectByCredentials(email: String, password: String) =
    sql"""
      SELECT username, email, bio, image
      FROM users
      WHERE email = $email AND password = $password
    """.query[(String, String, Option[String], Option[String])].option.transact(xa)

  override def create(input: NewUser): F[User] =
    insert(input).as(
      User(
        email = input.email,
        token = "jwt.token.here",
        username = input.username,
        bio = None,
        image = None
      )
    )

  override def authenticate(email: String, password: String): F[Option[User]] =
    selectByCredentials(email, password).map(
      _.map { case (username, userEmail, bio, image) =>
        User(
          email = userEmail,
          token = "jwt.token.here",
          username = username,
          bio = bio,
          image = image
        )
      }
    )

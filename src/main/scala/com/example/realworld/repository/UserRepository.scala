package com.example.realworld.repository

import cats.effect.Async
import cats.syntax.functor.*
import com.example.realworld.model.NewUser
import com.example.realworld.model.User
import doobie.implicits.*
import doobie.util.transactor.Transactor

trait UserRepository[F[_]]:
  def create(input: NewUser): F[User]

final class DoobieUserRepository[F[_]: Async](xa: Transactor[F]) extends UserRepository[F]:
  private def insert(input: NewUser) =
    sql"""
      INSERT INTO users (username, email, password, bio, image)
      VALUES (${input.username}, ${input.email}, ${input.password}, NULL, NULL)
    """.update.run.transact(xa)

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

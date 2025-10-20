package com.example.realworld.repository

import cats.effect.Async
import cats.syntax.functor.*
import com.example.realworld.model.NewUser
import doobie.Read
import doobie.Transactor
import doobie.syntax.all.*

trait UserRepository[F[_]]:
  def create(input: NewUser): F[StoredUser]
  def authenticate(email: String, password: String): F[Option[StoredUser]]
  def findByEmail(email: String): F[Option[StoredUser]]

final case class StoredUser(
    email: String,
    username: String,
    bio: Option[String],
    image: Option[String]
) derives Read

final class DoobieUserRepository[F[_]: Async](xa: Transactor[F]) extends UserRepository[F]:
  private def selectByEmail(email: String) =
    sql"""
      SELECT email, username, bio, image
      FROM users
      WHERE email = $email
    """.query[StoredUser].option.transact(xa)

  private def insert(input: NewUser) =
    sql"""
      INSERT INTO users (username, email, password, bio, image)
      VALUES (${input.username}, ${input.email}, ${input.password}, NULL, NULL)
    """.update.run.transact(xa)

  private def selectByCredentials(email: String, password: String)(using Read[StoredUser]) =
    sql"""
      SELECT email, username, bio, image
      FROM users
      WHERE email = $email AND password = $password
    """.query[StoredUser].option.transact(xa)

  override def create(input: NewUser): F[StoredUser] =
    insert(input).as(
      StoredUser(
        email = input.email,
        username = input.username,
        bio = None,
        image = None
      )
    )

  override def authenticate(email: String, password: String): F[Option[StoredUser]] =
    selectByCredentials(email, password)

  override def findByEmail(email: String): F[Option[StoredUser]] =
    selectByEmail(email)

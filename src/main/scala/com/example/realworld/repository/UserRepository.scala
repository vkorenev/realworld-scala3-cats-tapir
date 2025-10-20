package com.example.realworld.repository

import cats.effect.Async
import cats.syntax.all.*
import com.example.realworld.model.NewUser
import com.example.realworld.model.UserId
import doobie.Read
import doobie.Transactor
import doobie.syntax.all.*

trait UserRepository[F[_]]:
  def create(input: NewUser): F[StoredUser]
  def authenticate(email: String, password: String): F[Option[StoredUser]]
  def findById(id: UserId): F[Option[StoredUser]]

final case class StoredUser(
    id: UserId,
    email: String,
    username: String,
    bio: Option[String],
    image: Option[String]
) derives Read

final class DoobieUserRepository[F[_]: Async](xa: Transactor[F]) extends UserRepository[F]:
  override def create(input: NewUser): F[StoredUser] =
    sql"""
      INSERT INTO users (username, email, password, bio, image)
      VALUES (${input.username}, ${input.email}, ${input.password}, NULL, NULL)
    """.update
      .withUniqueGeneratedKeys[Long]("id")
      .transact(xa)
      .map { id =>
        StoredUser(
          id = UserId(id),
          email = input.email,
          username = input.username,
          bio = None,
          image = None
        )
      }

  override def authenticate(email: String, password: String): F[Option[StoredUser]] =
    sql"""
      SELECT id, email, username, bio, image
      FROM users
      WHERE email = $email AND password = $password
    """.query[StoredUser].option.transact(xa)

  override def findById(id: UserId): F[Option[StoredUser]] =
    sql"""
      SELECT id, email, username, bio, image
      FROM users
      WHERE id = $id
    """.query[StoredUser].option.transact(xa)

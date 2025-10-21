package com.example.realworld.repository

import cats.effect.Async
import cats.syntax.all.*
import com.example.realworld.model.NewUser
import com.example.realworld.model.UserId
import com.example.realworld.security.PasswordHasher
import com.example.realworld.security.Pbkdf2PasswordHasher
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

final class DoobieUserRepository[F[_]: Async](
    xa: Transactor[F],
    passwordHasher: PasswordHasher[F]
) extends UserRepository[F]:
  override def create(input: NewUser): F[StoredUser] =
    for
      hashedPassword <- passwordHasher.hash(input.password)
      id <-
        sql"""
          INSERT INTO users (username, email, password, bio, image)
          VALUES (${input.username}, ${input.email}, $hashedPassword, NULL, NULL)
        """.update
          .withUniqueGeneratedKeys[Long]("id")
          .transact(xa)
    yield StoredUser(
      id = UserId(id),
      email = input.email,
      username = input.username,
      bio = None,
      image = None
    )

  override def authenticate(email: String, password: String): F[Option[StoredUser]] =
    sql"""
      SELECT id, email, username, bio, image, password
      FROM users
      WHERE email = $email
    """
      .query[(StoredUser, String)]
      .option
      .transact(xa)
      .flatMap {
        case Some((user, storedHash)) =>
          passwordHasher.verify(password, storedHash).map { matches =>
            if matches then Some(user) else None
          }
        case None =>
          Async[F].pure(None)
      }

  override def findById(id: UserId): F[Option[StoredUser]] =
    sql"""
      SELECT id, email, username, bio, image
      FROM users
      WHERE id = $id
    """.query[StoredUser].option.transact(xa)

object DoobieUserRepository:
  def apply[F[_]: Async](xa: Transactor[F]): DoobieUserRepository[F] =
    new DoobieUserRepository[F](xa, Pbkdf2PasswordHasher[F]())

  def apply[F[_]: Async](
      xa: Transactor[F],
      passwordHasher: PasswordHasher[F]
  ): DoobieUserRepository[F] =
    new DoobieUserRepository[F](xa, passwordHasher)

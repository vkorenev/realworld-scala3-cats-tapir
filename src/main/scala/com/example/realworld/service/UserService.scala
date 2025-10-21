package com.example.realworld.service

import cats.effect.Async
import cats.effect.Sync
import cats.syntax.all.*
import com.example.realworld.Unauthorized
import com.example.realworld.auth.AuthToken
import com.example.realworld.model.NewUser
import com.example.realworld.model.UpdateUser
import com.example.realworld.model.User
import com.example.realworld.model.UserId
import com.example.realworld.repository.StoredUser
import com.example.realworld.repository.UserRepository

trait UserService[F[_]]:
  def register(input: NewUser): F[User]
  def authenticate(email: String, password: String): F[Option[User]]
  def findById(userId: UserId): F[User]
  def update(userId: UserId, update: UpdateUser): F[User]

final class LiveUserService[F[_]: Sync](userRepository: UserRepository[F]) extends UserService[F]:
  private def attachToken(user: StoredUser): User =
    User(
      email = user.email,
      token = AuthToken.issue(user.id),
      username = user.username,
      bio = user.bio,
      image = user.image
    )

  override def register(input: NewUser): F[User] =
    userRepository.create(input).map(attachToken)

  override def authenticate(email: String, password: String): F[Option[User]] =
    userRepository.authenticate(email, password).map(_.map(attachToken))

  override def findById(userId: UserId): F[User] =
    userRepository.findById(userId).flatMap {
      case Some(user) => Sync[F].delay(attachToken(user))
      case None => Sync[F].raiseError(Unauthorized())
    }

  override def update(userId: UserId, update: UpdateUser): F[User] =
    userRepository.update(userId, update).flatMap {
      case Some(user) => Sync[F].delay(attachToken(user))
      case None => Sync[F].raiseError(Unauthorized())
    }

object UserService:
  def live[F[_]: Async](userRepository: UserRepository[F]): UserService[F] =
    new LiveUserService(userRepository)

  def apply[F[_]: Async](userRepository: UserRepository[F]): UserService[F] =
    live(userRepository)

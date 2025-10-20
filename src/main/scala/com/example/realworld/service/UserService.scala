package com.example.realworld.service

import cats.effect.Async
import cats.syntax.all.*
import com.example.realworld.auth.AuthToken
import com.example.realworld.model.NewUser
import com.example.realworld.model.User
import com.example.realworld.repository.StoredUser
import com.example.realworld.repository.UserRepository

trait UserService[F[_]]:
  def register(input: NewUser): F[User]
  def authenticate(email: String, password: String): F[Option[User]]
  def findByToken(token: String): F[Option[User]]

final class LiveUserService[F[_]: Async](userRepository: UserRepository[F]) extends UserService[F]:
  private def attachToken(user: StoredUser): User =
    User(
      email = user.email,
      token = AuthToken.issue(user.email),
      username = user.username,
      bio = user.bio,
      image = user.image
    )

  override def register(input: NewUser): F[User] =
    userRepository.create(input).map(attachToken)

  override def authenticate(email: String, password: String): F[Option[User]] =
    userRepository.authenticate(email, password).map(_.map(attachToken))

  override def findByToken(token: String): F[Option[User]] =
    AuthToken
      .resolve[F](token)
      .flatMap(email => userRepository.findByEmail(email))
      .map(_.map(attachToken))
      .handleError(_ => None)

object UserService:
  def live[F[_]: Async](userRepository: UserRepository[F]): UserService[F] =
    new LiveUserService(userRepository)

  def apply[F[_]: Async](userRepository: UserRepository[F]): UserService[F] =
    live(userRepository)

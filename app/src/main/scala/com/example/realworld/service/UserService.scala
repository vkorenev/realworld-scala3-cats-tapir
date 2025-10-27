package com.example.realworld.service
import cats.effect.Async
import cats.effect.Sync
import cats.syntax.all.*
import com.example.realworld.NotFound
import com.example.realworld.Unauthorized
import com.example.realworld.auth.AuthToken
import com.example.realworld.model.NewUser
import com.example.realworld.model.Profile
import com.example.realworld.model.UpdateUser
import com.example.realworld.model.User
import com.example.realworld.model.UserId
import com.example.realworld.repository.StoredProfile
import com.example.realworld.repository.StoredUser
import com.example.realworld.repository.UserRepository

trait UserService[F[_]]:
  def register(input: NewUser): F[User]
  def authenticate(email: String, password: String): F[Option[User]]
  def findById(userId: UserId): F[User]
  def update(userId: UserId, update: UpdateUser): F[User]
  def findProfile(viewer: Option[UserId], username: String): F[Option[Profile]]
  def follow(followerId: UserId, username: String): F[Profile]
  def unfollow(followerId: UserId, username: String): F[Profile]

final class LiveUserService[F[_]: Sync](
    userRepository: UserRepository[F],
    authToken: AuthToken[F]
) extends UserService[F]:
  private def attachToken(user: StoredUser): F[User] =
    authToken.issue(user.id).map { token =>
      User(
        email = user.email,
        token = token,
        username = user.username,
        bio = user.bio,
        image = user.image
      )
    }

  override def register(input: NewUser): F[User] =
    userRepository.create(input).flatMap(attachToken)

  override def authenticate(email: String, password: String): F[Option[User]] =
    userRepository.authenticate(email, password).flatMap {
      case Some(user) => attachToken(user).map(_.some)
      case None => Sync[F].pure(None)
    }

  override def findById(userId: UserId): F[User] =
    userRepository.findById(userId).flatMap {
      case Some(user) => attachToken(user)
      case None => Sync[F].raiseError(Unauthorized())
    }

  override def update(userId: UserId, update: UpdateUser): F[User] =
    userRepository.update(userId, update).flatMap {
      case Some(user) => attachToken(user)
      case None => Sync[F].raiseError(Unauthorized())
    }

  private def toProfile(profile: StoredProfile): Profile =
    Profile(
      username = profile.user.username,
      bio = profile.user.bio,
      image = profile.user.image,
      following = profile.following
    )

  override def findProfile(viewer: Option[UserId], username: String): F[Option[Profile]] =
    userRepository.findProfile(viewer, username).map(_.map(toProfile))

  override def follow(followerId: UserId, username: String): F[Profile] =
    userRepository
      .follow(followerId, username)
      .flatMap {
        case Some(profile) => Sync[F].pure(toProfile(profile))
        case None => Sync[F].raiseError(NotFound())
      }

  override def unfollow(followerId: UserId, username: String): F[Profile] =
    userRepository
      .unfollow(followerId, username)
      .flatMap {
        case Some(profile) => Sync[F].pure(toProfile(profile))
        case None => Sync[F].raiseError(NotFound())
      }

object UserService:
  def live[F[_]: Async](
      userRepository: UserRepository[F],
      authToken: AuthToken[F]
  ): UserService[F] =
    new LiveUserService(userRepository, authToken)

  def apply[F[_]: Async](
      userRepository: UserRepository[F],
      authToken: AuthToken[F]
  ): UserService[F] =
    live(userRepository, authToken)

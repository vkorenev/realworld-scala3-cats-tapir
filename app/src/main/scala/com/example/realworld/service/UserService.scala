package com.example.realworld.service

import cats.effect.Async
import cats.syntax.all.*
import com.example.realworld.NotFound
import com.example.realworld.Unauthorized
import com.example.realworld.auth.AuthToken
import com.example.realworld.db.Queries
import com.example.realworld.db.StoredProfile
import com.example.realworld.db.StoredUser
import com.example.realworld.model.NewUser
import com.example.realworld.model.Profile
import com.example.realworld.model.UpdateUser
import com.example.realworld.model.User
import com.example.realworld.model.UserId
import com.example.realworld.security.PasswordHasher
import doobie.ConnectionIO
import doobie.Transactor
import doobie.implicits.*

trait UserService[F[_]]:
  def register(input: NewUser): F[User]
  def authenticate(email: String, password: String): F[Option[User]]
  def findById(userId: UserId): F[User]
  def update(userId: UserId, update: UpdateUser): F[User]
  def findProfile(viewer: Option[UserId], username: String): F[Option[Profile]]
  def follow(followerId: UserId, username: String): F[Profile]
  def unfollow(followerId: UserId, username: String): F[Profile]

final class LiveUserService[F[_]: Async](
    xa: Transactor[F],
    passwordHasher: PasswordHasher[F],
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
    for
      hashedPassword <- passwordHasher.hash(input.password)
      id <- Queries
        .insertUser(input.username, input.email, hashedPassword)
        .withUniqueGeneratedKeys[Long]("id")
        .transact(xa)
      user = StoredUser(
        id = UserId(id),
        email = input.email,
        username = input.username,
        bio = None,
        image = None
      )
      result <- attachToken(user)
    yield result

  override def authenticate(email: String, password: String): F[Option[User]] =
    Queries
      .selectUserByEmailWithPassword(email)
      .option
      .transact(xa)
      .flatMap {
        case Some((user, storedHash)) =>
          passwordHasher.verify(password, storedHash).flatMap { matches =>
            if matches then attachToken(user).map(_.some) else Async[F].pure(None)
          }
        case None => Async[F].pure(None)
      }

  override def findById(userId: UserId): F[User] =
    Queries
      .selectUserById(userId)
      .option
      .transact(xa)
      .flatMap {
        case Some(user) => attachToken(user)
        case None => Async[F].raiseError(Unauthorized())
      }

  override def update(userId: UserId, update: UpdateUser): F[User] =
    update.password.traverse(passwordHasher.hash).flatMap { hashedPasswordOpt =>
      Queries
        .selectUserByIdForUpdate(userId)
        .option
        .flatMap {
          case Some((existing, currentPasswordHash)) =>
            val updatedEmail = update.email.getOrElse(existing.email)
            val updatedUsername = update.username.getOrElse(existing.username)
            val updatedBio = update.bio.orElse(existing.bio)
            val updatedImage = update.image.orElse(existing.image)
            val updatedPasswordHash = hashedPasswordOpt.getOrElse(currentPasswordHash)

            Queries
              .updateUser(
                id = userId,
                email = updatedEmail,
                username = updatedUsername,
                bio = updatedBio,
                image = updatedImage,
                passwordHash = updatedPasswordHash
              )
              .run
              .as(
                Some(
                  existing.copy(
                    email = updatedEmail,
                    username = updatedUsername,
                    bio = updatedBio,
                    image = updatedImage
                  )
                )
              )
          case None => Option.empty[StoredUser].pure[ConnectionIO]
        }
        .transact(xa)
        .flatMap {
          case Some(user) => attachToken(user)
          case None => Async[F].raiseError(Unauthorized())
        }
    }

  override def findProfile(viewer: Option[UserId], username: String): F[Option[Profile]] =
    (for
      maybeUser <- Queries.selectUserByUsername(username).option
      profile <- maybeUser match
        case Some(user) =>
          viewer match
            case Some(viewerId) if viewerId != user.id =>
              Queries
                .selectFollowing(viewerId, user.id)
                .unique
                .map(isFollowing => Some(StoredProfile(user, following = isFollowing)))
            case _ =>
              Option(StoredProfile(user, following = false)).pure[ConnectionIO]
        case None =>
          Option.empty[StoredProfile].pure[ConnectionIO]
    yield profile.map(_.toProfile)).transact(xa)

  override def follow(followerId: UserId, username: String): F[Profile] =
    (for
      maybeUser <- Queries.selectUserByUsername(username).option
      profile <- maybeUser match
        case Some(user) =>
          val action =
            if followerId == user.id then ().pure[ConnectionIO]
            else Queries.insertFollow(followerId, user.id).run.void
          action.as(Some(StoredProfile(user, following = followerId != user.id)))
        case None =>
          Option.empty[StoredProfile].pure[ConnectionIO]
    yield profile)
      .transact(xa)
      .flatMap {
        case Some(profile) => Async[F].pure(profile.toProfile)
        case None => Async[F].raiseError(NotFound())
      }

  override def unfollow(followerId: UserId, username: String): F[Profile] =
    (for
      maybeUser <- Queries.selectUserByUsername(username).option
      profile <- maybeUser match
        case Some(user) =>
          val action =
            if followerId == user.id then ().pure[ConnectionIO]
            else Queries.deleteFollow(followerId, user.id).run.void
          action.as(Some(StoredProfile(user, following = false)))
        case None =>
          Option.empty[StoredProfile].pure[ConnectionIO]
    yield profile)
      .transact(xa)
      .flatMap {
        case Some(profile) => Async[F].pure(profile.toProfile)
        case None => Async[F].raiseError(NotFound())
      }

object UserService:
  def live[F[_]: Async](
      xa: Transactor[F],
      passwordHasher: PasswordHasher[F],
      authToken: AuthToken[F]
  ): UserService[F] =
    LiveUserService(xa, passwordHasher, authToken)

  def apply[F[_]: Async](
      xa: Transactor[F],
      passwordHasher: PasswordHasher[F],
      authToken: AuthToken[F]
  ): UserService[F] =
    live(xa, passwordHasher, authToken)

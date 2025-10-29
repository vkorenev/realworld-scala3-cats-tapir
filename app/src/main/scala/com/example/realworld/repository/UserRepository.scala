package com.example.realworld.repository

import cats.effect.Async
import cats.syntax.all.*
import com.example.realworld.db.Queries
import com.example.realworld.model.NewUser
import com.example.realworld.model.UpdateUser
import com.example.realworld.model.UserId
import com.example.realworld.security.PasswordHasher
import doobie.ConnectionIO
import doobie.Read
import doobie.Transactor
import doobie.syntax.all.*

trait UserRepository[F[_]]:
  def create(input: NewUser): F[StoredUser]
  def authenticate(email: String, password: String): F[Option[StoredUser]]
  def findById(id: UserId): F[Option[StoredUser]]
  def update(id: UserId, update: UpdateUser): F[Option[StoredUser]]
  def findProfile(viewer: Option[UserId], username: String): F[Option[StoredProfile]]
  def follow(followerId: UserId, username: String): F[Option[StoredProfile]]
  def unfollow(followerId: UserId, username: String): F[Option[StoredProfile]]

final case class StoredUser(
    id: UserId,
    email: String,
    username: String,
    bio: Option[String],
    image: Option[String]
) derives Read

final case class StoredProfile(
    user: StoredUser,
    following: Boolean
)

final case class DoobieUserRepository[F[_]: Async](
    xa: Transactor[F],
    passwordHasher: PasswordHasher[F]
) extends UserRepository[F]:
  override def create(input: NewUser): F[StoredUser] =
    for
      hashedPassword <- passwordHasher.hash(input.password)
      id <-
        Queries
          .insertUser(input.username, input.email, hashedPassword)
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
    Queries
      .selectUserByEmailWithPassword(email)
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
    Queries.selectUserById(id).option.transact(xa)

  override def update(id: UserId, update: UpdateUser): F[Option[StoredUser]] =
    update.password.traverse(passwordHasher.hash).flatMap { hashedPasswordOpt =>
      Queries
        .selectUserByIdForUpdate(id)
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
                id = id,
                email = updatedEmail,
                username = updatedUsername,
                bio = updatedBio,
                image = updatedImage,
                passwordHash = updatedPasswordHash
              )
              .run
              .as {
                Some(
                  existing.copy(
                    email = updatedEmail,
                    username = updatedUsername,
                    bio = updatedBio,
                    image = updatedImage
                  )
                )
              }
          case None =>
            Option.empty[StoredUser].pure[ConnectionIO]
        }
        .transact(xa)
    }

  override def findProfile(viewer: Option[UserId], username: String): F[Option[StoredProfile]] =
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
    yield profile).transact(xa)

  override def follow(followerId: UserId, username: String): F[Option[StoredProfile]] =
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
    yield profile).transact(xa)

  override def unfollow(followerId: UserId, username: String): F[Option[StoredProfile]] =
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
    yield profile).transact(xa)

package com.example.realworld.repository

import cats.effect.Async
import cats.syntax.all.*
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
  private def selectByUsername(username: String): ConnectionIO[Option[StoredUser]] =
    sql"""
      SELECT id, email, username, bio, image
      FROM users
      WHERE username = $username
    """.query[StoredUser].option

  private def selectFollowing(followerId: UserId, followedId: UserId): ConnectionIO[Boolean] =
    sql"""
      SELECT EXISTS (
        SELECT 1
        FROM user_follows
        WHERE follower_id = $followerId AND followed_id = $followedId
      )
    """.query[Boolean].unique

  private def insertFollow(followerId: UserId, followedId: UserId): ConnectionIO[Unit] =
    sql"""
      INSERT INTO user_follows (follower_id, followed_id)
      VALUES ($followerId, $followedId)
      ON CONFLICT DO NOTHING
    """.update.run.void

  private def deleteFollow(followerId: UserId, followedId: UserId): ConnectionIO[Unit] =
    sql"""
      DELETE FROM user_follows
      WHERE follower_id = $followerId AND followed_id = $followedId
    """.update.run.void

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

  override def update(id: UserId, update: UpdateUser): F[Option[StoredUser]] =
    update.password.traverse(passwordHasher.hash).flatMap { hashedPasswordOpt =>
      sql"""
        SELECT id, email, username, bio, image, password
        FROM users
        WHERE id = $id
        FOR UPDATE
      """
        .query[(StoredUser, String)]
        .option
        .flatMap {
          case Some((existing, currentPasswordHash)) =>
            val updatedEmail = update.email.getOrElse(existing.email)
            val updatedUsername = update.username.getOrElse(existing.username)
            val updatedBio = update.bio match
              case some @ Some(_) => some
              case None => existing.bio
            val updatedImage = update.image match
              case some @ Some(_) => some
              case None => existing.image
            val updatedPasswordHash = hashedPasswordOpt.getOrElse(currentPasswordHash)

            sql"""
              UPDATE users
              SET email = $updatedEmail,
                  username = $updatedUsername,
                  bio = $updatedBio,
                  image = $updatedImage,
                  password = $updatedPasswordHash
              WHERE id = $id
            """.update.run.as {
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
      maybeUser <- selectByUsername(username)
      profile <- maybeUser match
        case Some(user) =>
          viewer match
            case Some(viewerId) if viewerId != user.id =>
              selectFollowing(viewerId, user.id).map(isFollowing =>
                Some(StoredProfile(user, following = isFollowing))
              )
            case _ =>
              Option(StoredProfile(user, following = false)).pure[ConnectionIO]
        case None =>
          Option.empty[StoredProfile].pure[ConnectionIO]
    yield profile).transact(xa)

  override def follow(followerId: UserId, username: String): F[Option[StoredProfile]] =
    (for
      maybeUser <- selectByUsername(username)
      profile <- maybeUser match
        case Some(user) =>
          val action =
            if followerId == user.id then ().pure[ConnectionIO]
            else insertFollow(followerId, user.id)
          action.as(Some(StoredProfile(user, following = followerId != user.id)))
        case None =>
          Option.empty[StoredProfile].pure[ConnectionIO]
    yield profile).transact(xa)

  override def unfollow(followerId: UserId, username: String): F[Option[StoredProfile]] =
    (for
      maybeUser <- selectByUsername(username)
      profile <- maybeUser match
        case Some(user) =>
          val action =
            if followerId == user.id then ().pure[ConnectionIO]
            else deleteFollow(followerId, user.id)
          action.as(Some(StoredProfile(user, following = false)))
        case None =>
          Option.empty[StoredProfile].pure[ConnectionIO]
    yield profile).transact(xa)

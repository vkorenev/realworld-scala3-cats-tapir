package com.example.realworld.repository

import cats.ApplicativeThrow
import cats.effect.Async
import cats.syntax.all.*
import com.example.realworld.NotFound
import com.example.realworld.Unauthorized
import com.example.realworld.db.Queries
import com.example.realworld.model.ArticleId
import com.example.realworld.model.CommentId
import com.example.realworld.model.UserId
import doobie.ConnectionIO
import doobie.Read
import doobie.Transactor
import doobie.implicits.*
import doobie.postgres.implicits.*

import java.time.Instant

trait CommentRepository[F[_]]:
  def create(authorId: UserId, slug: String, body: String, at: Instant): F[StoredComment]
  def list(slug: String, viewerId: Option[UserId]): F[List[StoredComment]]
  def delete(authorId: UserId, slug: String, commentId: CommentId): F[Unit]

final case class StoredComment(
    id: CommentId,
    body: String,
    createdAt: Instant,
    updatedAt: Instant,
    author: StoredProfile
) derives Read

final class DoobieCommentRepository[F[_]: Async](xa: Transactor[F]) extends CommentRepository[F]:

  private def getArticleId(slug: String): ConnectionIO[ArticleId] =
    Queries
      .selectArticleIdBySlug(slug)
      .option
      .flatMap(ApplicativeThrow[ConnectionIO].fromOption(_, NotFound()))

  override def create(
      authorId: UserId,
      slug: String,
      body: String,
      at: Instant
  ): F[StoredComment] =
    (for
      articleId <- getArticleId(slug)
      commentId <- Queries
        .insertComment(articleId, authorId, body, at)
        .withUniqueGeneratedKeys[CommentId]("id")
      created <- Queries.selectCommentById(commentId, slug, viewerId = Some(authorId)).unique
    yield created).transact(xa)

  override def list(slug: String, viewerId: Option[UserId]): F[List[StoredComment]] =
    (for
      _ <- getArticleId(slug)
      comments <- Queries.selectCommentsBySlug(slug, viewerId).to[List]
    yield comments).transact(xa)

  override def delete(authorId: UserId, slug: String, commentId: CommentId): F[Unit] =
    (for
      _ <- getArticleId(slug)
      existingOpt <- Queries
        .selectCommentById(commentId, slug, viewerId = Some(authorId))
        .option
      existing <- ApplicativeThrow[ConnectionIO].fromOption(existingOpt, NotFound())
      _ <- ApplicativeThrow[ConnectionIO].raiseWhen(existing.author.user.id != authorId)(
        Unauthorized()
      )
      _ <- Queries.deleteComment(commentId).run.void
    yield ()).transact(xa)

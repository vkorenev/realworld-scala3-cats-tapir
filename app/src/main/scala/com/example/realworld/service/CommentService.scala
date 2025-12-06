package com.example.realworld.service

import cats.ApplicativeThrow
import cats.effect.Async
import cats.syntax.all.*
import com.example.realworld.NotFound
import com.example.realworld.Unauthorized
import com.example.realworld.db.Queries
import com.example.realworld.model.ArticleId
import com.example.realworld.model.Comment
import com.example.realworld.model.CommentId
import com.example.realworld.model.MultipleCommentsResponse
import com.example.realworld.model.UserId
import doobie.ConnectionIO
import doobie.Transactor
import doobie.implicits.*

trait CommentService[F[_]]:
  def add(authorId: UserId, slug: String, body: String): F[Comment]
  def list(viewerId: Option[UserId], slug: String): F[MultipleCommentsResponse]
  def delete(authorId: UserId, slug: String, commentId: CommentId): F[Unit]

final class LiveCommentService[F[_]: Async](xa: Transactor[F]) extends CommentService[F]:

  private def getArticleId(slug: String): ConnectionIO[ArticleId] =
    Queries
      .selectArticleIdBySlug(slug)
      .option
      .flatMap(ApplicativeThrow[ConnectionIO].fromOption(_, NotFound()))

  override def add(authorId: UserId, slug: String, body: String): F[Comment] =
    for
      at <- Async[F].realTimeInstant
      created <- (for
        articleId <- getArticleId(slug)
        commentId <- Queries
          .insertComment(articleId, authorId, body, at)
          .withUniqueGeneratedKeys[CommentId]("id")
        created <- Queries.selectCommentById(commentId, slug, viewerId = Some(authorId)).unique
      yield created).transact(xa)
    yield created.toComment

  override def list(viewerId: Option[UserId], slug: String): F[MultipleCommentsResponse] =
    (for
      _ <- getArticleId(slug)
      comments <- Queries.selectCommentsBySlug(slug, viewerId).to[List]
    yield MultipleCommentsResponse(comments.map(_.toComment))).transact(xa)

  override def delete(authorId: UserId, slug: String, commentId: CommentId): F[Unit] =
    (for
      _ <- getArticleId(slug)
      existingOpt <- Queries.selectCommentById(commentId, slug, viewerId = Some(authorId)).option
      existing <- ApplicativeThrow[ConnectionIO].fromOption(existingOpt, NotFound())
      _ <- ApplicativeThrow[ConnectionIO].raiseWhen(existing.author.user.id != authorId)(
        Unauthorized()
      )
      _ <- Queries.deleteComment(commentId).run.void
    yield ()).transact(xa)

object CommentService:
  def live[F[_]: Async](xa: Transactor[F]): CommentService[F] = LiveCommentService(xa)

  def apply[F[_]: Async](xa: Transactor[F]): CommentService[F] = live(xa)

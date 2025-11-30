package com.example.realworld.service

import cats.effect.Async
import cats.syntax.all.*
import com.example.realworld.model.Comment
import com.example.realworld.model.CommentId
import com.example.realworld.model.MultipleCommentsResponse
import com.example.realworld.model.Profile
import com.example.realworld.model.UserId
import com.example.realworld.repository.CommentRepository
import com.example.realworld.repository.StoredComment

trait CommentService[F[_]]:
  def add(authorId: UserId, slug: String, body: String): F[Comment]
  def list(viewerId: Option[UserId], slug: String): F[MultipleCommentsResponse]
  def delete(authorId: UserId, slug: String, commentId: CommentId): F[Unit]

final class LiveCommentService[F[_]: Async](commentRepository: CommentRepository[F])
    extends CommentService[F]:

  private def toComment(stored: StoredComment): Comment =
    Comment(
      id = stored.id,
      createdAt = stored.createdAt,
      updatedAt = stored.updatedAt,
      body = stored.body,
      author = Profile(
        username = stored.author.user.username,
        bio = stored.author.user.bio,
        image = stored.author.user.image,
        following = stored.author.following
      )
    )

  override def add(authorId: UserId, slug: String, body: String): F[Comment] =
    for
      at <- Async[F].realTimeInstant
      created <- commentRepository.create(authorId, slug, body, at)
    yield toComment(created)

  override def list(viewerId: Option[UserId], slug: String): F[MultipleCommentsResponse] =
    commentRepository
      .list(slug, viewerId)
      .map(comments => MultipleCommentsResponse(comments.map(toComment)))

  override def delete(authorId: UserId, slug: String, commentId: CommentId): F[Unit] =
    commentRepository.delete(authorId, slug, commentId)

object CommentService:
  def live[F[_]: Async](commentRepository: CommentRepository[F]): CommentService[F] =
    LiveCommentService(commentRepository)

  def apply[F[_]: Async](commentRepository: CommentRepository[F]): CommentService[F] =
    live(commentRepository)

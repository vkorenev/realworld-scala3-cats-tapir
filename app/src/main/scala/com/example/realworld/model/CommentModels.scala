package com.example.realworld.model

import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec
import sttp.tapir.Schema

import java.time.Instant

final case class NewComment(body: String) derives ConfiguredJsonValueCodec, Schema

final case class NewCommentRequest(comment: NewComment) derives ConfiguredJsonValueCodec, Schema

final case class Comment(
    id: CommentId,
    createdAt: Instant,
    updatedAt: Instant,
    body: String,
    author: Profile
) derives ConfiguredJsonValueCodec,
      Schema

final case class CommentResponse(comment: Comment) derives ConfiguredJsonValueCodec, Schema

final case class MultipleCommentsResponse(comments: List[Comment])
    derives ConfiguredJsonValueCodec,
      Schema

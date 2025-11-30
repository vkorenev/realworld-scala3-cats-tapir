package com.example.realworld.model

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import doobie.util.meta.Meta
import sttp.tapir.Schema

opaque type CommentId = Long

object CommentId:
  inline def apply(value: Long): CommentId = value
  inline def value(id: CommentId): Long = id
  given Meta[CommentId] = Meta[Long].imap(apply)(value)
  given JsonValueCodec[CommentId] = JsonCodecMaker.make
  // Schema.map expects a partial inverse; wrap in Some to satisfy Option return
  given Schema[CommentId] = Schema.schemaForLong.map(id => Some(apply(id)))(value)

package com.example.realworld.model

import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec
import sttp.tapir.Schema

final case class TagsResponse(tags: List[String]) derives ConfiguredJsonValueCodec, Schema

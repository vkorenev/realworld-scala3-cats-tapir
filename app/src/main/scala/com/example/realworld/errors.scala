package com.example.realworld

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import sttp.tapir.Schema

case class Unauthorized() extends Exception derives Schema

object Unauthorized:
  given JsonValueCodec[Unauthorized] = JsonCodecMaker.make

case class NotFound() extends Exception derives Schema

object NotFound:
  given JsonValueCodec[NotFound] = JsonCodecMaker.make

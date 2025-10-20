package com.example.realworld.auth

import cats.MonadThrow
import cats.syntax.all.*
import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.github.plokhotnyuk.jsoniter_scala.core.writeToString
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import pdi.jwt.Jwt
import pdi.jwt.JwtAlgorithm
import pdi.jwt.JwtClaim

object AuthToken:
  private val SecretKey = "change-me"
  private val Algorithm = JwtAlgorithm.HS256
  final private case class TokenPayload(email: String)
  private given JsonValueCodec[TokenPayload] = JsonCodecMaker.make

  def issue(email: String): String =
    val claim = JwtClaim(content = writeToString(TokenPayload(email)))
    Jwt.encode(claim, SecretKey, Algorithm)

  def resolve[F[_]: MonadThrow](token: String): F[String] =
    MonadThrow[F]
      .fromTry(
        Jwt.decode(token, SecretKey, Seq(Algorithm))
      )
      .flatMap { claim =>
        MonadThrow[F].catchNonFatal {
          readFromString[TokenPayload](claim.content).email
        }
      }

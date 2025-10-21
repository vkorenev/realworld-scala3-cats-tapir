package com.example.realworld.auth

import cats.MonadThrow
import cats.syntax.all.*
import com.example.realworld.model.UserId
import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.github.plokhotnyuk.jsoniter_scala.core.writeToString
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import pdi.jwt.Jwt
import pdi.jwt.JwtAlgorithm
import pdi.jwt.JwtClaim

trait AuthToken[F[_]]:
  def issue(userId: UserId): F[String]
  def resolve(token: String): F[UserId]

case class JwtAuthToken[F[_]: MonadThrow]() extends AuthToken[F]:
  import JwtAuthToken.*

  override def issue(userId: UserId): F[String] =
    MonadThrow[F].catchNonFatal {
      val claim = JwtClaim(content = writeToString(TokenPayload(UserId.value(userId))))
      Jwt.encode(claim, SecretKey, Algorithm)
    }

  override def resolve(token: String): F[UserId] =
    MonadThrow[F]
      .fromTry(
        Jwt.decode(token, SecretKey, Seq(Algorithm))
      )
      .flatMap { claim =>
        MonadThrow[F].catchNonFatal {
          UserId(readFromString[TokenPayload](claim.content).userId)
        }
      }

object JwtAuthToken:
  private val SecretKey = "change-me"
  private val Algorithm = JwtAlgorithm.HS256
  final private case class TokenPayload(userId: Long)
  private given JsonValueCodec[TokenPayload] = JsonCodecMaker.make

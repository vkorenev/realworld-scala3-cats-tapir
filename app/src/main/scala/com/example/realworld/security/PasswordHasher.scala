package com.example.realworld.security

import cats.effect.Sync
import org.typelevel.otel4s.trace.Tracer

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

trait PasswordHasher[F[_]]:
  def hash(password: String): F[String]
  def verify(password: String, stored: String): F[Boolean]

final class Pbkdf2PasswordHasher[F[_]: {Sync, Tracer}] private (
    iterations: Int,
    keyLengthBits: Int
) extends PasswordHasher[F]:
  import Pbkdf2PasswordHasher.*

  private val random = SecureRandom();
  private val encoder = Base64.getEncoder
  private val decoder = Base64.getDecoder

  override def hash(password: String): F[String] =
    Tracer[F].span("hash-password").use { _ =>
      Sync[F].delay {
        val salt = new Array[Byte](16)
        random.nextBytes(salt)
        val hashBytes = deriveKey(
          password.toCharArray,
          Algorithms(StoredAlgorithmId),
          salt,
          iterations,
          keyLengthBits
        )
        s"$StoredAlgorithmId:$iterations:${encoder.encodeToString(salt)}:${encoder.encodeToString(hashBytes)}"
      }
    }

  override def verify(password: String, stored: String): F[Boolean] =
    Tracer[F].span("verify-password").use { _ =>
      Sync[F].delay {
        val Array(algorithm, iterationsString, saltB64, hashB64) = stored.split(":")
        val effectiveIterations = iterationsString.toIntOption.getOrElse(iterations)
        val salt = decoder.decode(saltB64)
        val expected = decoder.decode(hashB64)
        val actual = deriveKey(
          password.toCharArray,
          Algorithms(algorithm),
          salt,
          effectiveIterations,
          expected.length * 8
        )
        MessageDigest.isEqual(actual, expected)
      }
    }

  private def deriveKey(
      password: Array[Char],
      algorithm: String,
      salt: Array[Byte],
      iterations: Int,
      keyLengthBits: Int
  ): Array[Byte] =
    val spec = PBEKeySpec(password, salt, iterations, keyLengthBits)
    try SecretKeyFactory.getInstance(algorithm).generateSecret(spec).getEncoded
    finally spec.clearPassword()

object Pbkdf2PasswordHasher:
  private val DefaultIterations = 65536
  private val KeyLengthBits = 256
  private val StoredAlgorithmId = "pbkdf2-hmac-sha256"
  private val Algorithms = Map(StoredAlgorithmId -> "PBKDF2WithHmacSHA256")

  def apply[F[_]: Sync: Tracer](
      iterations: Int = DefaultIterations,
      keyLengthBits: Int = KeyLengthBits
  ): PasswordHasher[F] =
    new Pbkdf2PasswordHasher[F](iterations, keyLengthBits)

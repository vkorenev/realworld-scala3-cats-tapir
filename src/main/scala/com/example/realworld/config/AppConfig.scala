package com.example.realworld.config

import cats.effect.Sync
import doobie.hikari.Config as HikariConfig
import pureconfig.ConfigReader
import pureconfig.ConfigSource
import pureconfig.generic.semiauto.*
import pureconfig.module.catseffect.syntax.*

final case class JwtConfig(secretKey: String) derives ConfigReader

final case class AppConfig(database: HikariConfig, jwt: JwtConfig) derives ConfigReader

object AppConfig:
  given ConfigReader[HikariConfig] = deriveReader

  def load[F[_]: Sync]: F[AppConfig] =
    ConfigSource.default.loadF[F, AppConfig]()

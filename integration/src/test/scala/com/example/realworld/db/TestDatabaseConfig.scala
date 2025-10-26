package com.example.realworld.db

import com.dimafeng.testcontainers.PostgreSQLContainer
import doobie.hikari.Config as HikariConfig

object TestDatabaseConfig:
  def fromContainer(container: PostgreSQLContainer): HikariConfig =
    HikariConfig(
      driverClassName = Some(container.driverClassName),
      jdbcUrl = container.jdbcUrl,
      username = Some(container.username),
      password = Some(container.password)
    )

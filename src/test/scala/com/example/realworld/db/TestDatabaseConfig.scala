package com.example.realworld.db

import doobie.hikari.Config as HikariConfig

object TestDatabaseConfig:
  def forTest(name: String): HikariConfig =
    HikariConfig(
      driverClassName = Some("org.h2.Driver"),
      jdbcUrl = s"jdbc:h2:mem:$name;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_UPPER=false",
      username = Some("sa"),
      password = None
    )

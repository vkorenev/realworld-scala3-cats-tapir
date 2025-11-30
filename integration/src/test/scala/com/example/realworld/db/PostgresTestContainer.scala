package com.example.realworld.db

import cats.effect.Async
import cats.effect.Resource
import com.dimafeng.testcontainers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

object PostgresTestContainer:
  private val image = DockerImageName.parse("postgres:17-alpine")

  def resource[F[_]: Async](databaseName: String): Resource[F, PostgreSQLContainer] =
    Resource.make {
      Async[F].blocking {
        val container = PostgreSQLContainer(
          dockerImageNameOverride = image,
          databaseName = databaseName,
          username = "postgres",
          password = "postgres"
        )
        container.start()
        container
      }
    }(container => Async[F].blocking(container.stop()))

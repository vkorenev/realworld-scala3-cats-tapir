# ![RealWorld Example App](logo.png)

## Scala 3 with Cats & Tapir application that adheres to the [RealWorld](https://github.com/gothinkster/realworld) spec and API

This codebase was created to demonstrate a fully fledged backend application built with **Scala 3 with Cats & Tapir** including CRUD operations, authentication, routing, pagination, and more.

For more information on how to this works with other frontends/backends, head over to the [RealWorld](https://github.com/gothinkster/realworld) repo.

## How it works

### Tech stack

- Backend: Scala 3, Cats, Cats-Effect, FS2, Http4s + Tapir.
- Persistence: Doobie with PostgreSQL.
- Serialization/config: Jsoniter-Scala for JSON, Pureconfig for typed config.
- Auth: jwt-scala for JWT signing/verification.
- Testing: MUnit, Testcontainers for integration tests.
- Packaging: Jib for container image builds.
- Infrastructure: OpenTofu + Terragrunt; GCP Cloud Run deployment, Neon managed Postgres.

## Getting started

- Install and activate [mise-en-place](https://mise.jdx.dev/getting-started.html) development environment setup tool.

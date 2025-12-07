# ![RealWorld Example App](logo.png)

## Scala 3 with Cats & Tapir application that adheres to the [RealWorld](https://github.com/gothinkster/realworld) spec and API

This codebase was created to demonstrate a fully-fledged backend application built with **Scala 3 with Cats & Tapir** including CRUD operations, authentication, routing, pagination, and more.

For more information on how this works with other frontends/backends, head over to the [RealWorld](https://github.com/gothinkster/realworld) repo.

### Tech stack

- Backend: Scala 3, Cats, Cats-Effect, FS2, Http4s + Tapir.
- Persistence: Doobie with PostgreSQL.
- Serialization/config: Jsoniter-Scala for JSON, Pureconfig for typed config.
- Auth: jwt-scala for JWT signing/verification.
- Testing: MUnit, Testcontainers for integration tests.
- Packaging: Jib for container image builds.
- Infrastructure: OpenTofu + Terragrunt; GCP Cloud Run deployment, Neon managed Postgres.

## Development

### Prerequisites

1. Install and activate [mise-en-place](https://mise.jdx.dev/getting-started.html) development environment setup tool
2. Install Docker using one of the following options:
   - [Docker Desktop](https://docs.docker.com/desktop/) (includes Docker Engine and Docker Compose)
   - [Docker Engine](https://docs.docker.com/engine/install/) with [Docker Compose](https://docs.docker.com/compose/install/)

### Tools setup

Use `mise` to install required tools (Java, sbt, OpenTofu, Terragrunt):

```bash
mise trust
mise install
```

### Development commands

| Command | Description |
|---------|-------------|
| `sbt compile` | Compile the project |
| `sbt test` | Run tests |
| `sbt scalafmtAll` | Format all Scala files |
| `sbt scalafmtCheckAll` | Check formatting without making changes |
| `sbt scalafixAll` | Run Scalafix linting rules and apply fixes |
| `sbt "scalafixAll --check"` | Check linting rules without applying fixes |
| `sbt "clean; coverage; test; coverageReport"` | Run tests with code coverage |

### Running the application locally

First, build and publish the container image locally:

```bash
sbt app/jibJavaDockerBuild
```

Then run the application alongside PostgreSQL with Docker Compose:

```bash
docker compose up -d
```

The server will start on <http://localhost:8080>. Swagger UI is available at <http://localhost:8080/docs>.

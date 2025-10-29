package com.example.realworld

import cats.effect.IO
import cats.effect.Resource
import com.example.realworld.auth.JwtAuthToken
import com.example.realworld.db.Database
import com.example.realworld.db.PostgresTestContainer
import com.example.realworld.db.TestDatabaseConfig
import com.example.realworld.model.ArticleResponse
import com.example.realworld.model.ProfileResponse
import com.example.realworld.model.UserId
import com.example.realworld.model.UserResponse
import com.example.realworld.repository.DoobieArticleRepository
import com.example.realworld.repository.DoobieUserRepository
import com.example.realworld.security.Pbkdf2PasswordHasher
import com.example.realworld.service.ArticleService
import com.example.realworld.service.UserService
import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import fs2.Stream
import munit.CatsEffectSuite
import org.http4s.AuthScheme
import org.http4s.Credentials
import org.http4s.Headers
import org.http4s.MediaType
import org.http4s.Method
import org.http4s.Request
import org.http4s.Response
import org.http4s.Status
import org.http4s.headers.Authorization
import org.http4s.headers.`Content-Type`
import org.http4s.implicits.*

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

class HttpServerSpec extends CatsEffectSuite:
  private val jwtSecret = "test-secret-key"
  private val authToken = JwtAuthToken[IO](jwtSecret)
  private val httpAppFixture = ResourceSuiteLocalFixture(
    "http-app",
    for
      dbName <- Resource.eval(IO(s"http-app-${UUID.randomUUID().toString.replace("-", "")}"))
      container <- PostgresTestContainer.resource[IO](dbName)
      transactor <- Database.transactor[IO](TestDatabaseConfig.fromContainer(container))
      _ <- Resource.eval(Database.initialize[IO](transactor))
      userRepository = DoobieUserRepository[IO](transactor, Pbkdf2PasswordHasher[IO]())
      userService = UserService.live[IO](userRepository, authToken)
      articleRepository = DoobieArticleRepository[IO](transactor)
      articleService = ArticleService.live[IO](articleRepository)
    yield Endpoints[IO](userService, articleService, authToken).routes.orNotFound
  )

  override def munitFixtures = List(httpAppFixture)

  private def assertUserPayload(
      response: Response[IO],
      expectedEmail: String,
      expectedUsername: String,
      expectedBio: Option[String] = None,
      expectedImage: Option[String] = None
  ): IO[(UserResponse, UserId)] =
    for
      body <- response.as[String]
      decoded = readFromString[UserResponse](body)
      _ = assertEquals(decoded.user.email, expectedEmail)
      _ = assertEquals(decoded.user.username, expectedUsername)
      _ = assertEquals(decoded.user.bio, expectedBio)
      _ = assertEquals(decoded.user.image, expectedImage)
      userId <- authToken.resolve(decoded.user.token)
      _ = assert(UserId.value(userId) > 0, clue(decoded.user.token))
    yield (decoded, userId)

  private def assertProfilePayload(
      response: Response[IO],
      expectedUsername: String,
      expectedBio: Option[String],
      expectedImage: Option[String],
      expectedFollowing: Boolean
  ): IO[Unit] =
    for
      body <- response.as[String]
      decoded = readFromString[ProfileResponse](body)
      _ = assertEquals(decoded.profile.username, expectedUsername)
      _ = assertEquals(decoded.profile.bio, expectedBio)
      _ = assertEquals(decoded.profile.image, expectedImage)
      _ = assertEquals(decoded.profile.following, expectedFollowing)
    yield ()

  private def assertArticlePayload(
      response: Response[IO],
      expectedSlug: String,
      expectedTitle: String,
      expectedDescription: String,
      expectedBody: String,
      expectedTags: List[String],
      expectedAuthorUsername: String,
      expectedFavorited: Boolean = false,
      expectedFavoritesCount: Int = 0,
      expectedFollowing: Boolean = false
  ): IO[ArticleResponse] =
    for
      body <- response.as[String]
      decoded = readFromString[ArticleResponse](body)
      article = decoded.article
      _ = assertEquals(article.slug, expectedSlug)
      _ = assertEquals(article.title, expectedTitle)
      _ = assertEquals(article.description, expectedDescription)
      _ = assertEquals(article.body, expectedBody)
      _ = assertEquals(article.tagList, expectedTags)
      _ = assertEquals(article.favorited, expectedFavorited)
      _ = assertEquals(article.favoritesCount, expectedFavoritesCount)
      _ = assert(!article.createdAt.isBefore(Instant.EPOCH), clue(article.createdAt))
      _ = assert(!article.updatedAt.isBefore(article.createdAt), clue(article.updatedAt))
      _ = assertEquals(article.author.username, expectedAuthorUsername)
      _ = assertEquals(article.author.following, expectedFollowing)
      _ = assertEquals(article.author.bio, None)
      _ = assertEquals(article.author.image, None)
    yield decoded

  test("liveness endpoint returns no content"):
    val httpApp = httpAppFixture()
    val request = Request[IO](Method.GET, uri"/__health/liveness")
    httpApp
      .run(request)
      .map(response => assertEquals(response.status, Status.NoContent))

  test("register user endpoint returns created user payload"):
    val httpApp = httpAppFixture()
    val payload =
      """{"user":{"username":"Jacob","email":"jake@jake.jake","password":"jakejake"}}"""
    val request = Request[IO](
      Method.POST,
      uri"/api/users",
      headers = Headers(`Content-Type`(MediaType.application.json)),
      body = Stream.emits(payload.getBytes(StandardCharsets.UTF_8)).covary[IO]
    )

    httpApp
      .run(request)
      .flatMap { response =>
        assertEquals(response.status, Status.Ok)
        assertUserPayload(response, "jake@jake.jake", "Jacob").map(_ => ())
      }

  test("create article endpoint creates a new article for the authenticated user"):
    val httpApp = httpAppFixture()
    val registerPayload =
      """{"user":{"username":"Jake","email":"jake@example.com","password":"jakepassword"}}"""
    val registerRequest = Request[IO](
      Method.POST,
      uri"/api/users",
      headers = Headers(`Content-Type`(MediaType.application.json)),
      body = Stream.emits(registerPayload.getBytes(StandardCharsets.UTF_8)).covary[IO]
    )

    val articlePayload =
      """{"article":{"title":"How to train your dragon","description":"Ever wonder how?","body":"You have to believe","tagList":["reactjs","angularjs","dragons"]}}"""

    val result =
      for
        registerResponse <- httpApp.run(registerRequest)
        _ = assertEquals(registerResponse.status, Status.Ok)
        (registeredUserResponse, _) <-
          assertUserPayload(registerResponse, "jake@example.com", "Jake")
        token = registeredUserResponse.user.token
        createRequest = Request[IO](
          Method.POST,
          uri"/api/articles",
          headers = Headers(
            Authorization(Credentials.Token(AuthScheme.Bearer, token)),
            `Content-Type`(MediaType.application.json)
          ),
          body = Stream.emits(articlePayload.getBytes(StandardCharsets.UTF_8)).covary[IO]
        )
        createResponse <- httpApp.run(createRequest)
        _ = assertEquals(createResponse.status, Status.Created)
        articleResponse <-
          assertArticlePayload(
            createResponse,
            expectedSlug = "how-to-train-your-dragon",
            expectedTitle = "How to train your dragon",
            expectedDescription = "Ever wonder how?",
            expectedBody = "You have to believe",
            expectedTags = List("reactjs", "angularjs", "dragons"),
            expectedAuthorUsername = "Jake"
          )
        _ = assertEquals(articleResponse.article.author.username, "Jake")
      yield ()

    result

  test("get article endpoint returns article by slug without authentication"):
    val httpApp = httpAppFixture()
    val registerPayload =
      """{"user":{"username":"Jessie","email":"jessie@example.com","password":"secretpass"}}"""
    val registerRequest = Request[IO](
      Method.POST,
      uri"/api/users",
      headers = Headers(`Content-Type`(MediaType.application.json)),
      body = Stream.emits(registerPayload.getBytes(StandardCharsets.UTF_8)).covary[IO]
    )

    val articlePayload =
      """{"article":{"title":"Exploring Rust","description":"Borrow checker fun","body":"Rust is great","tagList":["rust","systems"]}}"""

    val result =
      for
        registerResponse <- httpApp.run(registerRequest)
        _ = assertEquals(registerResponse.status, Status.Ok)
        (registeredUserResponse, _) <-
          assertUserPayload(registerResponse, "jessie@example.com", "Jessie")
        token = registeredUserResponse.user.token
        createRequest = Request[IO](
          Method.POST,
          uri"/api/articles",
          headers = Headers(
            Authorization(Credentials.Token(AuthScheme.Bearer, token)),
            `Content-Type`(MediaType.application.json)
          ),
          body = Stream.emits(articlePayload.getBytes(StandardCharsets.UTF_8)).covary[IO]
        )
        createResponse <- httpApp.run(createRequest)
        _ = assertEquals(createResponse.status, Status.Created)
        createdArticle <-
          assertArticlePayload(
            createResponse,
            expectedSlug = "exploring-rust",
            expectedTitle = "Exploring Rust",
            expectedDescription = "Borrow checker fun",
            expectedBody = "Rust is great",
            expectedTags = List("rust", "systems"),
            expectedAuthorUsername = "Jessie"
          )
        getRequest = Request[IO](
          Method.GET,
          uri"/api/articles" / createdArticle.article.slug
        )
        getResponse <- httpApp.run(getRequest)
        _ = assertEquals(getResponse.status, Status.Ok)
        _ <-
          assertArticlePayload(
            getResponse,
            expectedSlug = "exploring-rust",
            expectedTitle = "Exploring Rust",
            expectedDescription = "Borrow checker fun",
            expectedBody = "Rust is great",
            expectedTags = List("rust", "systems"),
            expectedAuthorUsername = "Jessie"
          )
      yield ()

    result

  test("update article endpoint updates existing article for the author"):
    val httpApp = httpAppFixture()
    val registerPayload =
      """{"user":{"username":"Author","email":"author@example.com","password":"secretpass"}}"""
    val registerRequest = Request[IO](
      Method.POST,
      uri"/api/users",
      headers = Headers(`Content-Type`(MediaType.application.json)),
      body = Stream.emits(registerPayload.getBytes(StandardCharsets.UTF_8)).covary[IO]
    )

    val createPayload =
      """{"article":{"title":"Training dragons","description":"A guide","body":"Believe in yourself","tagList":["dragons"]}}"""

    val updatePayload =
      """{"article":{"title":"Did you train your dragon?"}}"""

    val result =
      for
        registerResponse <- httpApp.run(registerRequest)
        _ = assertEquals(registerResponse.status, Status.Ok)
        (userResponse, _) <- assertUserPayload(registerResponse, "author@example.com", "Author")
        token = userResponse.user.token
        createRequest = Request[IO](
          Method.POST,
          uri"/api/articles",
          headers = Headers(
            Authorization(Credentials.Token(AuthScheme.Bearer, token)),
            `Content-Type`(MediaType.application.json)
          ),
          body = Stream.emits(createPayload.getBytes(StandardCharsets.UTF_8)).covary[IO]
        )
        createResponse <- httpApp.run(createRequest)
        _ = assertEquals(createResponse.status, Status.Created)
        createdArticle <-
          assertArticlePayload(
            createResponse,
            expectedSlug = "training-dragons",
            expectedTitle = "Training dragons",
            expectedDescription = "A guide",
            expectedBody = "Believe in yourself",
            expectedTags = List("dragons"),
            expectedAuthorUsername = "Author"
          )
        updateRequest = Request[IO](
          Method.PUT,
          uri"/api/articles" / createdArticle.article.slug,
          headers = Headers(
            Authorization(Credentials.Token(AuthScheme.Bearer, token)),
            `Content-Type`(MediaType.application.json)
          ),
          body = Stream.emits(updatePayload.getBytes(StandardCharsets.UTF_8)).covary[IO]
        )
        updateResponse <- httpApp.run(updateRequest)
        _ = assertEquals(updateResponse.status, Status.Ok)
        updatedArticle <-
          assertArticlePayload(
            updateResponse,
            expectedSlug = "did-you-train-your-dragon",
            expectedTitle = "Did you train your dragon?",
            expectedDescription = "A guide",
            expectedBody = "Believe in yourself",
            expectedTags = List("dragons"),
            expectedAuthorUsername = "Author"
          )
        _ = assert(!updatedArticle.article.updatedAt.isBefore(createdArticle.article.updatedAt))
        getRequest = Request[IO](Method.GET, uri"/api/articles" / updatedArticle.article.slug)
        getResponse <- httpApp.run(getRequest)
        _ = assertEquals(getResponse.status, Status.Ok)
        _ <-
          assertArticlePayload(
            getResponse,
            expectedSlug = "did-you-train-your-dragon",
            expectedTitle = "Did you train your dragon?",
            expectedDescription = "A guide",
            expectedBody = "Believe in yourself",
            expectedTags = List("dragons"),
            expectedAuthorUsername = "Author"
          )
      yield ()

    result

  test("delete article endpoint removes article for the author"):
    val httpApp = httpAppFixture()
    val registerPayload =
      """{"user":{"username":"Remover","email":"remover@example.com","password":"secretpass"}}"""
    val registerRequest = Request[IO](
      Method.POST,
      uri"/api/users",
      headers = Headers(`Content-Type`(MediaType.application.json)),
      body = Stream.emits(registerPayload.getBytes(StandardCharsets.UTF_8)).covary[IO]
    )

    val createPayload =
      """{"article":{"title":"Disposable article","description":"Temporary","body":"To be deleted","tagList":["temp"]}}"""

    val result =
      for
        registerResponse <- httpApp.run(registerRequest)
        _ = assertEquals(registerResponse.status, Status.Ok)
        (userResponse, _) <- assertUserPayload(registerResponse, "remover@example.com", "Remover")
        token = userResponse.user.token
        createRequest = Request[IO](
          Method.POST,
          uri"/api/articles",
          headers = Headers(
            Authorization(Credentials.Token(AuthScheme.Bearer, token)),
            `Content-Type`(MediaType.application.json)
          ),
          body = Stream.emits(createPayload.getBytes(StandardCharsets.UTF_8)).covary[IO]
        )
        createResponse <- httpApp.run(createRequest)
        _ = assertEquals(createResponse.status, Status.Created)
        createdArticle <-
          assertArticlePayload(
            createResponse,
            expectedSlug = "disposable-article",
            expectedTitle = "Disposable article",
            expectedDescription = "Temporary",
            expectedBody = "To be deleted",
            expectedTags = List("temp"),
            expectedAuthorUsername = "Remover"
          )
        deleteRequest = Request[IO](
          Method.DELETE,
          uri"/api/articles" / createdArticle.article.slug,
          headers = Headers(Authorization(Credentials.Token(AuthScheme.Bearer, token)))
        )
        deleteResponse <- httpApp.run(deleteRequest)
        _ = assertEquals(deleteResponse.status, Status.NoContent)
        getRequest = Request[IO](Method.GET, uri"/api/articles" / createdArticle.article.slug)
        getResponse <- httpApp.run(getRequest)
        _ = assertEquals(getResponse.status, Status.NotFound)
      yield ()

    result

  test("favorite article endpoint toggles favorite status for the reader"):
    val httpApp = httpAppFixture()
    val authorPayload =
      """{"user":{"username":"FavAuthor","email":"author-fav@example.com","password":"secretpass"}}"""
    val readerPayload =
      """{"user":{"username":"FavReader","email":"reader@example.com","password":"anotherpass"}}"""
    val articlePayload =
      """{"article":{"title":"Favorite me","description":"Please do","body":"Pretty please","tagList":["tag"]}}"""

    val authorRequest = Request[IO](
      Method.POST,
      uri"/api/users",
      headers = Headers(`Content-Type`(MediaType.application.json)),
      body = Stream.emits(authorPayload.getBytes(StandardCharsets.UTF_8)).covary[IO]
    )

    val readerRequest = Request[IO](
      Method.POST,
      uri"/api/users",
      headers = Headers(`Content-Type`(MediaType.application.json)),
      body = Stream.emits(readerPayload.getBytes(StandardCharsets.UTF_8)).covary[IO]
    )

    val createBody = Stream.emits(articlePayload.getBytes(StandardCharsets.UTF_8)).covary[IO]

    val result =
      for
        authorResponse <- httpApp.run(authorRequest)
        _ = assertEquals(authorResponse.status, Status.Ok)
        (authorUserResponse, _) <-
          assertUserPayload(authorResponse, "author-fav@example.com", "FavAuthor")
        authorToken = authorUserResponse.user.token
        createRequest = Request[IO](
          Method.POST,
          uri"/api/articles",
          headers = Headers(
            Authorization(Credentials.Token(AuthScheme.Bearer, authorToken)),
            `Content-Type`(MediaType.application.json)
          ),
          body = createBody
        )
        createResponse <- httpApp.run(createRequest)
        _ = assertEquals(createResponse.status, Status.Created)
        createdArticle <-
          assertArticlePayload(
            createResponse,
            expectedSlug = "favorite-me",
            expectedTitle = "Favorite me",
            expectedDescription = "Please do",
            expectedBody = "Pretty please",
            expectedTags = List("tag"),
            expectedAuthorUsername = "FavAuthor"
          )
        readerResponse <- httpApp.run(readerRequest)
        _ = assertEquals(readerResponse.status, Status.Ok)
        (readerUserResponse, _) <-
          assertUserPayload(readerResponse, "reader@example.com", "FavReader")
        readerToken = readerUserResponse.user.token
        favoriteRequest = Request[IO](
          Method.POST,
          uri"/api/articles" / createdArticle.article.slug / "favorite",
          headers = Headers(Authorization(Credentials.Token(AuthScheme.Bearer, readerToken)))
        )
        favoriteResponse <- httpApp.run(favoriteRequest)
        _ = assertEquals(favoriteResponse.status, Status.Ok)
        _ <-
          assertArticlePayload(
            favoriteResponse,
            expectedSlug = createdArticle.article.slug,
            expectedTitle = "Favorite me",
            expectedDescription = "Please do",
            expectedBody = "Pretty please",
            expectedTags = List("tag"),
            expectedAuthorUsername = "FavAuthor",
            expectedFavorited = true,
            expectedFavoritesCount = 1
          )
        getFavoritedRequest = Request[IO](
          Method.GET,
          uri"/api/articles" / createdArticle.article.slug,
          headers = Headers(Authorization(Credentials.Token(AuthScheme.Bearer, readerToken)))
        )
        getFavoritedResponse <- httpApp.run(getFavoritedRequest)
        _ = assertEquals(getFavoritedResponse.status, Status.Ok)
        _ <-
          assertArticlePayload(
            getFavoritedResponse,
            expectedSlug = createdArticle.article.slug,
            expectedTitle = "Favorite me",
            expectedDescription = "Please do",
            expectedBody = "Pretty please",
            expectedTags = List("tag"),
            expectedAuthorUsername = "FavAuthor",
            expectedFavorited = true,
            expectedFavoritesCount = 1
          )
        unfavoriteRequest = Request[IO](
          Method.DELETE,
          uri"/api/articles" / createdArticle.article.slug / "favorite",
          headers = Headers(Authorization(Credentials.Token(AuthScheme.Bearer, readerToken)))
        )
        unfavoriteResponse <- httpApp.run(unfavoriteRequest)
        _ = assertEquals(unfavoriteResponse.status, Status.Ok)
        _ <-
          assertArticlePayload(
            unfavoriteResponse,
            expectedSlug = createdArticle.article.slug,
            expectedTitle = "Favorite me",
            expectedDescription = "Please do",
            expectedBody = "Pretty please",
            expectedTags = List("tag"),
            expectedAuthorUsername = "FavAuthor",
            expectedFavorited = false,
            expectedFavoritesCount = 0
          )
      yield ()

    result

  test("profile endpoint returns user profile without authentication"):
    val httpApp = httpAppFixture()
    val registerPayload =
      """{"user":{"username":"Mallory","email":"mallory@example.com","password":"password"}}"""
    val registerRequest = Request[IO](
      Method.POST,
      uri"/api/users",
      headers = Headers(`Content-Type`(MediaType.application.json)),
      body = Stream.emits(registerPayload.getBytes(StandardCharsets.UTF_8)).covary[IO]
    )

    val result =
      for
        registerResponse <- httpApp.run(registerRequest)
        _ = assertEquals(registerResponse.status, Status.Ok)
        _ <- assertUserPayload(registerResponse, "mallory@example.com", "Mallory")
        profileRequest = Request[IO](Method.GET, uri"/api/profiles/Mallory")
        profileResponse <- httpApp.run(profileRequest)
        _ = assertEquals(profileResponse.status, Status.Ok)
        _ <- assertProfilePayload(profileResponse, "Mallory", None, None, expectedFollowing = false)
      yield ()

    result

  test("follow and unfollow profile endpoints update following status when authenticated"):
    val httpApp = httpAppFixture()
    val followerPayload =
      """{"user":{"username":"Follower","email":"follower@example.com","password":"secret"}}"""
    val followeePayload =
      """{"user":{"username":"Leader","email":"leader@example.com","password":"secret"}}"""

    val followerRequest = Request[IO](
      Method.POST,
      uri"/api/users",
      headers = Headers(`Content-Type`(MediaType.application.json)),
      body = Stream.emits(followerPayload.getBytes(StandardCharsets.UTF_8)).covary[IO]
    )

    val followeeRequest = Request[IO](
      Method.POST,
      uri"/api/users",
      headers = Headers(`Content-Type`(MediaType.application.json)),
      body = Stream.emits(followeePayload.getBytes(StandardCharsets.UTF_8)).covary[IO]
    )

    val result =
      for
        followerResponse <- httpApp.run(followerRequest)
        _ = assertEquals(followerResponse.status, Status.Ok)
        (followerUserResponse, _) <-
          assertUserPayload(followerResponse, "follower@example.com", "Follower")
        followeeResponse <- httpApp.run(followeeRequest)
        _ = assertEquals(followeeResponse.status, Status.Ok)
        _ <- assertUserPayload(followeeResponse, "leader@example.com", "Leader")
        authHeaders = Headers(
          Authorization(Credentials.Token(AuthScheme.Bearer, followerUserResponse.user.token))
        )
        preFollowResponse <-
          httpApp.run(Request[IO](Method.GET, uri"/api/profiles/Leader", headers = authHeaders))
        _ = assertEquals(preFollowResponse.status, Status.Ok)
        _ <- assertProfilePayload(
          preFollowResponse,
          "Leader",
          None,
          None,
          expectedFollowing = false
        )
        followResponse <-
          httpApp.run(
            Request[IO](Method.POST, uri"/api/profiles/Leader/follow", headers = authHeaders)
          )
        _ = assertEquals(followResponse.status, Status.Ok)
        _ <- assertProfilePayload(
          followResponse,
          "Leader",
          None,
          None,
          expectedFollowing = true
        )
        postFollowResponse <-
          httpApp.run(Request[IO](Method.GET, uri"/api/profiles/Leader", headers = authHeaders))
        _ = assertEquals(postFollowResponse.status, Status.Ok)
        _ <- assertProfilePayload(
          postFollowResponse,
          "Leader",
          None,
          None,
          expectedFollowing = true
        )
        unfollowResponse <-
          httpApp.run(
            Request[IO](Method.DELETE, uri"/api/profiles/Leader/follow", headers = authHeaders)
          )
        _ = assertEquals(unfollowResponse.status, Status.Ok)
        _ <- assertProfilePayload(
          unfollowResponse,
          "Leader",
          None,
          None,
          expectedFollowing = false
        )
        postUnfollowResponse <-
          httpApp.run(Request[IO](Method.GET, uri"/api/profiles/Leader", headers = authHeaders))
        _ = assertEquals(postUnfollowResponse.status, Status.Ok)
        _ <- assertProfilePayload(
          postUnfollowResponse,
          "Leader",
          None,
          None,
          expectedFollowing = false
        )
      yield ()

    result

  test("login user endpoint returns existing user payload"):
    val httpApp = httpAppFixture()
    val registerPayload =
      """{"user":{"username":"Alice","email":"alice@example.com","password":"wonderland"}}"""
    val loginPayload =
      """{"user":{"email":"alice@example.com","password":"wonderland"}}"""

    val registerRequest = Request[IO](
      Method.POST,
      uri"/api/users",
      headers = Headers(`Content-Type`(MediaType.application.json)),
      body = Stream.emits(registerPayload.getBytes(StandardCharsets.UTF_8)).covary[IO]
    )

    val loginRequest = Request[IO](
      Method.POST,
      uri"/api/users/login",
      headers = Headers(`Content-Type`(MediaType.application.json)),
      body = Stream.emits(loginPayload.getBytes(StandardCharsets.UTF_8)).covary[IO]
    )

    httpApp
      .run(registerRequest)
      .flatMap { response =>
        assertEquals(response.status, Status.Ok)
        assertUserPayload(response, "alice@example.com", "Alice")
      }
      .flatMap { case (_, registeredUserId) =>
        httpApp
          .run(loginRequest)
          .flatMap { response =>
            assertEquals(response.status, Status.Ok)
            assertUserPayload(response, "alice@example.com", "Alice").map { case (_, loginUserId) =>
              assertEquals(loginUserId, registeredUserId)
            }
          }
      }

  test("current user endpoint returns authenticated user payload"):
    val httpApp = httpAppFixture()
    val registerPayload =
      """{"user":{"username":"Carol","email":"carol@example.com","password":"secret"}}"""

    val registerRequest = Request[IO](
      Method.POST,
      uri"/api/users",
      headers = Headers(`Content-Type`(MediaType.application.json)),
      body = Stream.emits(registerPayload.getBytes(StandardCharsets.UTF_8)).covary[IO]
    )

    httpApp
      .run(registerRequest)
      .flatMap { response =>
        assertEquals(response.status, Status.Ok)
        assertUserPayload(response, "carol@example.com", "Carol")
      }
      .flatMap { case (registeredUserResponse, registeredUserId) =>
        val token = registeredUserResponse.user.token
        val currentUserRequest = Request[IO](
          Method.GET,
          uri"/api/user",
          headers = Headers(Authorization(Credentials.Token(AuthScheme.Bearer, token)))
        )

        httpApp
          .run(currentUserRequest)
          .flatMap { response =>
            assertEquals(response.status, Status.Ok)
            assertUserPayload(response, "carol@example.com", "Carol").map {
              case (currentUserResponse, currentUserId) =>
                assertEquals(currentUserId, registeredUserId)
                assertEquals(currentUserResponse.user.token, token)
            }
          }
      }

  test("update user endpoint updates the authenticated user"):
    val httpApp = httpAppFixture()
    val registerPayload =
      """{"user":{"username":"Eve","email":"eve@example.com","password":"password1"}}"""
    val updatePayload =
      """{"user":{"email":"eve@conduit.example","username":"EveUpdated","password":"newsecret","bio":"Updated bio","image":"https://example.com/avatar.png"}}"""
    val expectedEmail = "eve@conduit.example"
    val expectedUsername = "EveUpdated"
    val expectedBio = Some("Updated bio")
    val expectedImage = Some("https://example.com/avatar.png")

    val registerRequest = Request[IO](
      Method.POST,
      uri"/api/users",
      headers = Headers(`Content-Type`(MediaType.application.json)),
      body = Stream.emits(registerPayload.getBytes(StandardCharsets.UTF_8)).covary[IO]
    )

    val result =
      for
        registerResponse <- httpApp.run(registerRequest)
        _ = assertEquals(registerResponse.status, Status.Ok)
        (registeredUserResponse, registeredUserId) <-
          assertUserPayload(registerResponse, "eve@example.com", "Eve")
        token = registeredUserResponse.user.token
        updateRequest = Request[IO](
          Method.PUT,
          uri"/api/user",
          headers = Headers(
            Authorization(Credentials.Token(AuthScheme.Bearer, token)),
            `Content-Type`(MediaType.application.json)
          ),
          body = Stream.emits(updatePayload.getBytes(StandardCharsets.UTF_8)).covary[IO]
        )
        updateResponse <- httpApp.run(updateRequest)
        _ = assertEquals(updateResponse.status, Status.Ok)
        (updatedUserResponse, updatedUserId) <-
          assertUserPayload(
            updateResponse,
            expectedEmail,
            expectedUsername,
            expectedBio,
            expectedImage
          )
        _ = assertEquals(updatedUserId, registeredUserId)
        _ = assertEquals(updatedUserResponse.user.token, token)
        loginPayload =
          """{"user":{"email":"eve@conduit.example","password":"newsecret"}}"""
        loginRequest = Request[IO](
          Method.POST,
          uri"/api/users/login",
          headers = Headers(`Content-Type`(MediaType.application.json)),
          body = Stream.emits(loginPayload.getBytes(StandardCharsets.UTF_8)).covary[IO]
        )
        loginResponse <- httpApp.run(loginRequest)
        _ = assertEquals(loginResponse.status, Status.Ok)
        (loggedInUserResponse, loggedInUserId) <-
          assertUserPayload(
            loginResponse,
            expectedEmail,
            expectedUsername,
            expectedBio,
            expectedImage
          )
        _ = assertEquals(loggedInUserId, registeredUserId)
        _ = assertEquals(loggedInUserResponse.user.token, token)
        profileRequest = Request[IO](
          Method.GET,
          uri"/api/profiles" / expectedUsername,
          headers = Headers(Authorization(Credentials.Token(AuthScheme.Bearer, token)))
        )
        profileResponse <- httpApp.run(profileRequest)
        _ = assertEquals(profileResponse.status, Status.Ok)
        _ <- assertProfilePayload(
          profileResponse,
          expectedUsername,
          expectedBio,
          expectedImage,
          expectedFollowing = false
        )
      yield ()

    result

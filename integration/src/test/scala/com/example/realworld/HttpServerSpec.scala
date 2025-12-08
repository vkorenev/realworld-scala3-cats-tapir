package com.example.realworld

import cats.effect.IO
import cats.effect.Resource
import com.example.realworld.auth.JwtAuthToken
import com.example.realworld.db.Database
import com.example.realworld.db.PostgresTestContainer
import com.example.realworld.db.TestDatabaseConfig
import com.example.realworld.model.ArticleResponse
import com.example.realworld.model.CommentId
import com.example.realworld.model.CommentResponse
import com.example.realworld.model.MultipleArticlesResponse
import com.example.realworld.model.MultipleCommentsResponse
import com.example.realworld.model.ProfileResponse
import com.example.realworld.model.TagsResponse
import com.example.realworld.model.UserId
import com.example.realworld.model.UserResponse
import com.example.realworld.security.Pbkdf2PasswordHasher
import com.example.realworld.service.ArticleService
import com.example.realworld.service.CommentService
import com.example.realworld.service.UserService
import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import doobie.hikari.HikariTransactor
import munit.CatsEffectSuite
import org.http4s.AuthScheme
import org.http4s.Credentials
import org.http4s.HttpApp
import org.http4s.MediaType
import org.http4s.Method
import org.http4s.Request
import org.http4s.Response
import org.http4s.Status
import org.http4s.Uri
import org.http4s.headers.Authorization
import org.http4s.headers.`Content-Type`
import org.http4s.implicits.*
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer

import java.time.Instant
import java.util.UUID

class HttpServerSpec extends CatsEffectSuite:
  private val jwtSecret = "test-secret-key"
  private val authToken = JwtAuthToken[IO](jwtSecret)
  private given Meter[IO] = Meter.noop[IO]
  private given Tracer[IO] = Tracer.noop[IO]
  private val httpAppFixture = ResourceSuiteLocalFixture(
    "http-app",
    for
      dbName <- Resource.eval(IO(s"http-app-${UUID.randomUUID().toString.replace("-", "")}"))
      container <- PostgresTestContainer.resource[IO](dbName)
      transactor <- HikariTransactor.fromConfig[IO](TestDatabaseConfig.fromContainer(container))
      _ <- Resource.eval(Database.initialize[IO](transactor))
      passwordHasher = Pbkdf2PasswordHasher[IO]()
      userService = UserService.live[IO](transactor, passwordHasher, authToken)
      articleService = ArticleService.live[IO](transactor)
      commentService = CommentService.live[IO](transactor)
      endpoints = Endpoints[IO](userService, articleService, commentService, authToken)
    yield endpoints.routes(summon[Meter[IO]], summon[Tracer[IO]]).orNotFound
  )

  override def munitFixtures = List(httpAppFixture)

  private def authHeader(token: String): Authorization =
    Authorization(Credentials.Token(AuthScheme.Bearer, token))

  private def registerUser(
      httpApp: HttpApp[IO],
      username: String,
      email: String,
      password: String
  ): IO[(UserResponse, UserId)] =
    val payload =
      s"""{"user":{"username":"$username","email":"$email","password":"$password"}}"""
    val request = Request[IO](Method.POST, uri"/api/users")
      .withEntity(payload)
      .withContentType(`Content-Type`(MediaType.application.json))
    httpApp.run(request).flatMap { response =>
      assertEquals(response.status, Status.Ok)
      assertUserPayload(response, email, username)
    }

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
      expectedTags: Set[String],
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
      _ = assertEquals(article.tagList.toSet, expectedTags)
      _ = assertEquals(article.favorited, expectedFavorited)
      _ = assertEquals(article.favoritesCount, expectedFavoritesCount)
      _ = assert(!article.createdAt.isBefore(Instant.EPOCH), clue(article.createdAt))
      _ = assert(!article.updatedAt.isBefore(article.createdAt), clue(article.updatedAt))
      _ = assertEquals(article.author.username, expectedAuthorUsername)
      _ = assertEquals(article.author.following, expectedFollowing)
      _ = assertEquals(article.author.bio, None)
      _ = assertEquals(article.author.image, None)
    yield decoded

  private def assertCommentPayload(
      response: Response[IO],
      expectedBody: String,
      expectedAuthorUsername: String,
      expectedFollowing: Boolean = false
  ): IO[CommentResponse] =
    for
      body <- response.as[String]
      decoded = readFromString[CommentResponse](body)
      comment = decoded.comment
      _ = assertEquals(comment.body, expectedBody)
      _ = assert(CommentId.value(comment.id) > 0, clue(comment.id))
      _ = assert(!comment.createdAt.isBefore(Instant.EPOCH), clue(comment.createdAt))
      _ = assert(!comment.updatedAt.isBefore(comment.createdAt), clue(comment.updatedAt))
      _ = assertEquals(comment.author.username, expectedAuthorUsername)
      _ = assertEquals(comment.author.following, expectedFollowing)
      _ = assertEquals(comment.author.bio, None)
      _ = assertEquals(comment.author.image, None)
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
    val request = Request[IO](Method.POST, uri"/api/users")
      .withEntity(payload)
      .withContentType(`Content-Type`(MediaType.application.json))

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
    val registerRequest = Request[IO](Method.POST, uri"/api/users")
      .withEntity(registerPayload)
      .withContentType(`Content-Type`(MediaType.application.json))

    val articlePayload =
      """{"article":{"title":"How to train your dragon","description":"Ever wonder how?","body":"You have to believe","tagList":["reactjs","angularjs","dragons"]}}"""

    val result =
      for
        registerResponse <- httpApp.run(registerRequest)
        _ = assertEquals(registerResponse.status, Status.Ok)
        (registeredUserResponse, _) <-
          assertUserPayload(registerResponse, "jake@example.com", "Jake")
        token = registeredUserResponse.user.token
        createRequest = Request[IO](Method.POST, uri"/api/articles")
          .putHeaders(authHeader(token))
          .withEntity(articlePayload)
          .withContentType(`Content-Type`(MediaType.application.json))
        createResponse <- httpApp.run(createRequest)
        _ = assertEquals(createResponse.status, Status.Created)
        articleResponse <-
          assertArticlePayload(
            createResponse,
            expectedSlug = "how-to-train-your-dragon",
            expectedTitle = "How to train your dragon",
            expectedDescription = "Ever wonder how?",
            expectedBody = "You have to believe",
            expectedTags = Set("reactjs", "angularjs", "dragons"),
            expectedAuthorUsername = "Jake"
          )
        _ = assertEquals(articleResponse.article.author.username, "Jake")
      yield ()

    result

  test("get article endpoint returns article by slug without authentication"):
    val httpApp = httpAppFixture()
    val registerPayload =
      """{"user":{"username":"Jessie","email":"jessie@example.com","password":"secretpass"}}"""
    val registerRequest = Request[IO](Method.POST, uri"/api/users")
      .withEntity(registerPayload)
      .withContentType(`Content-Type`(MediaType.application.json))

    val articlePayload =
      """{"article":{"title":"Exploring Rust","description":"Borrow checker fun","body":"Rust is great","tagList":["rust","systems"]}}"""

    val result =
      for
        registerResponse <- httpApp.run(registerRequest)
        _ = assertEquals(registerResponse.status, Status.Ok)
        (registeredUserResponse, _) <-
          assertUserPayload(registerResponse, "jessie@example.com", "Jessie")
        token = registeredUserResponse.user.token
        createRequest = Request[IO](Method.POST, uri"/api/articles")
          .putHeaders(authHeader(token))
          .withEntity(articlePayload)
          .withContentType(`Content-Type`(MediaType.application.json))
        createResponse <- httpApp.run(createRequest)
        _ = assertEquals(createResponse.status, Status.Created)
        createdArticle <-
          assertArticlePayload(
            createResponse,
            expectedSlug = "exploring-rust",
            expectedTitle = "Exploring Rust",
            expectedDescription = "Borrow checker fun",
            expectedBody = "Rust is great",
            expectedTags = Set("rust", "systems"),
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
            expectedTags = Set("rust", "systems"),
            expectedAuthorUsername = "Jessie"
          )
      yield ()

    result

  test("list articles endpoint supports filters and pagination"):
    val httpApp = httpAppFixture()

    val firstArticlePayload =
      """{"article":{"title":"Scala FP Basics","description":"Intro to FP","body":"Pure functions are great","tagList":["scala","fp"]}}"""
    val secondArticlePayload =
      """{"article":{"title":"Scala Streams","description":"Streaming with FS2","body":"Compositional streams","tagList":["scala","fs2"]}}"""
    val thirdArticlePayload =
      """{"article":{"title":"Rust Ownership","description":"Borrow checker 101","body":"Ownership rules","tagList":["rust"]}}"""

    val result =
      for
        (authorOneResponse, _) <-
          registerUser(httpApp, "WriterOne", "writer1@example.com", "password1")
        (authorTwoResponse, _) <-
          registerUser(httpApp, "WriterTwo", "writer2@example.com", "password2")
        firstCreateResponse <-
          httpApp.run(
            Request[IO](Method.POST, uri"/api/articles")
              .putHeaders(authHeader(authorOneResponse.user.token))
              .withEntity(firstArticlePayload)
              .withContentType(`Content-Type`(MediaType.application.json))
          )
        _ = assertEquals(firstCreateResponse.status, Status.Created)
        firstArticle <-
          assertArticlePayload(
            firstCreateResponse,
            expectedSlug = "scala-fp-basics",
            expectedTitle = "Scala FP Basics",
            expectedDescription = "Intro to FP",
            expectedBody = "Pure functions are great",
            expectedTags = Set("scala", "fp"),
            expectedAuthorUsername = "WriterOne"
          )
        secondCreateResponse <-
          httpApp.run(
            Request[IO](Method.POST, uri"/api/articles")
              .putHeaders(authHeader(authorOneResponse.user.token))
              .withEntity(secondArticlePayload)
              .withContentType(`Content-Type`(MediaType.application.json))
          )
        _ = assertEquals(secondCreateResponse.status, Status.Created)
        secondArticle <-
          assertArticlePayload(
            secondCreateResponse,
            expectedSlug = "scala-streams",
            expectedTitle = "Scala Streams",
            expectedDescription = "Streaming with FS2",
            expectedBody = "Compositional streams",
            expectedTags = Set("scala", "fs2"),
            expectedAuthorUsername = "WriterOne"
          )
        thirdCreateResponse <-
          httpApp.run(
            Request[IO](Method.POST, uri"/api/articles")
              .putHeaders(authHeader(authorTwoResponse.user.token))
              .withEntity(thirdArticlePayload)
              .withContentType(`Content-Type`(MediaType.application.json))
          )
        _ = assertEquals(thirdCreateResponse.status, Status.Created)
        thirdArticle <-
          assertArticlePayload(
            thirdCreateResponse,
            expectedSlug = "rust-ownership",
            expectedTitle = "Rust Ownership",
            expectedDescription = "Borrow checker 101",
            expectedBody = "Ownership rules",
            expectedTags = Set("rust"),
            expectedAuthorUsername = "WriterTwo"
          )
        (fanResponse, _) <- registerUser(httpApp, "ArticleFan", "fan@example.com", "fanpass")
        favoriteRequest = Request[IO](
          Method.POST,
          uri"/api/articles" / firstArticle.article.slug / "favorite"
        ).putHeaders(authHeader(fanResponse.user.token))
        favoriteResponse <- httpApp.run(favoriteRequest)
        _ = assertEquals(favoriteResponse.status, Status.Ok)
        _ <-
          assertArticlePayload(
            favoriteResponse,
            expectedSlug = firstArticle.article.slug,
            expectedTitle = firstArticle.article.title,
            expectedDescription = firstArticle.article.description,
            expectedBody = firstArticle.article.body,
            expectedTags = firstArticle.article.tagList.toSet,
            expectedAuthorUsername = "WriterOne",
            expectedFavorited = true,
            expectedFavoritesCount = 1
          )
        listByTagResponse <- httpApp.run(
          Request[IO](Method.GET, uri"/api/articles?tag=scala&limit=1")
        )
        _ = assertEquals(listByTagResponse.status, Status.Ok)
        listByTag <- listByTagResponse.as[String].map(readFromString[MultipleArticlesResponse](_))
        _ = assertEquals(listByTag.articlesCount, 2)
        _ = assertEquals(listByTag.articles.map(_.slug), List(secondArticle.article.slug))
        listByTagPage2Response <-
          httpApp.run(Request[IO](Method.GET, uri"/api/articles?tag=scala&limit=1&offset=1"))
        _ = assertEquals(listByTagPage2Response.status, Status.Ok)
        listByTagPage2 <- listByTagPage2Response
          .as[String]
          .map(readFromString[MultipleArticlesResponse](_))
        _ = assertEquals(listByTagPage2.articlesCount, 2)
        _ = assertEquals(listByTagPage2.articles.map(_.slug), List(firstArticle.article.slug))
        listByAuthorResponse <- httpApp.run(
          Request[IO](Method.GET, uri"/api/articles?author=WriterTwo")
        )
        _ = assertEquals(listByAuthorResponse.status, Status.Ok)
        listByAuthor <- listByAuthorResponse
          .as[String]
          .map(readFromString[MultipleArticlesResponse](_))
        _ = assertEquals(listByAuthor.articlesCount, 1)
        _ = assertEquals(listByAuthor.articles.map(_.slug), List(thirdArticle.article.slug))
        favoritedResponse <-
          httpApp.run(
            Request[IO](
              Method.GET,
              uri"/api/articles?favorited=ArticleFan"
            ).putHeaders(authHeader(fanResponse.user.token))
          )
        _ = assertEquals(favoritedResponse.status, Status.Ok)
        favorited <- favoritedResponse.as[String].map(readFromString[MultipleArticlesResponse](_))
        _ = assertEquals(favorited.articlesCount, 1)
        favoritedArticle = favorited.articles.head
        _ = assertEquals(favoritedArticle.slug, firstArticle.article.slug)
        _ = assertEquals(favoritedArticle.favorited, true)
        _ = assertEquals(favoritedArticle.favoritesCount, 1)
      yield ()

    result

  test("list tags endpoint returns unique tags without authentication"):
    val httpApp = httpAppFixture()
    val registerPayload =
      """{"user":{"username":"TagCollector","email":"tagger@example.com","password":"secretpass"}}"""
    val registerRequest = Request[IO](Method.POST, uri"/api/users")
      .withEntity(registerPayload)
      .withContentType(`Content-Type`(MediaType.application.json))

    val firstArticlePayload =
      """{"article":{"title":"Tech trends","description":"Latest trends","body":"Stay curious","tagList":["reactjs","angularjs"]}}"""

    val secondArticlePayload =
      """{"article":{"title":"Dragon tales","description":"Stories","body":"Believe","tagList":["dragons","reactjs"]}}"""

    val result =
      for
        registerResponse <- httpApp.run(registerRequest)
        _ = assertEquals(registerResponse.status, Status.Ok)
        (userResponse, _) <- assertUserPayload(
          registerResponse,
          "tagger@example.com",
          "TagCollector"
        )
        token = userResponse.user.token
        firstCreateRequest = Request[IO](Method.POST, uri"/api/articles")
          .putHeaders(authHeader(token))
          .withEntity(firstArticlePayload)
          .withContentType(`Content-Type`(MediaType.application.json))
        firstCreateResponse <- httpApp.run(firstCreateRequest)
        _ = assertEquals(firstCreateResponse.status, Status.Created)
        secondCreateRequest = Request[IO](Method.POST, uri"/api/articles")
          .putHeaders(authHeader(token))
          .withEntity(secondArticlePayload)
          .withContentType(`Content-Type`(MediaType.application.json))
        secondCreateResponse <- httpApp.run(secondCreateRequest)
        _ = assertEquals(secondCreateResponse.status, Status.Created)
        tagsRequest = Request[IO](Method.GET, uri"/api/tags")
        tagsResponse <- httpApp.run(tagsRequest)
        _ = assertEquals(tagsResponse.status, Status.Ok)
        tagsBody <- tagsResponse.as[String]
        decoded = readFromString[TagsResponse](tagsBody)
        expectedTags = Set("angularjs", "dragons", "reactjs")
        _ = assertEquals(decoded.tags.sorted, decoded.tags)
        _ = assert(expectedTags.subsetOf(decoded.tags.toSet), clue(decoded.tags))
      yield ()

    result

  test("update article endpoint updates existing article for the author"):
    val httpApp = httpAppFixture()
    val registerPayload =
      """{"user":{"username":"Author","email":"author@example.com","password":"secretpass"}}"""
    val registerRequest = Request[IO](Method.POST, uri"/api/users")
      .withEntity(registerPayload)
      .withContentType(`Content-Type`(MediaType.application.json))

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
        createRequest = Request[IO](Method.POST, uri"/api/articles")
          .putHeaders(authHeader(token))
          .withEntity(createPayload)
          .withContentType(`Content-Type`(MediaType.application.json))
        createResponse <- httpApp.run(createRequest)
        _ = assertEquals(createResponse.status, Status.Created)
        createdArticle <-
          assertArticlePayload(
            createResponse,
            expectedSlug = "training-dragons",
            expectedTitle = "Training dragons",
            expectedDescription = "A guide",
            expectedBody = "Believe in yourself",
            expectedTags = Set("dragons"),
            expectedAuthorUsername = "Author"
          )
        updateRequest = Request[IO](Method.PUT, uri"/api/articles" / createdArticle.article.slug)
          .putHeaders(authHeader(token))
          .withEntity(updatePayload)
          .withContentType(`Content-Type`(MediaType.application.json))
        updateResponse <- httpApp.run(updateRequest)
        _ = assertEquals(updateResponse.status, Status.Ok)
        updatedArticle <-
          assertArticlePayload(
            updateResponse,
            expectedSlug = "did-you-train-your-dragon",
            expectedTitle = "Did you train your dragon?",
            expectedDescription = "A guide",
            expectedBody = "Believe in yourself",
            expectedTags = Set("dragons"),
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
            expectedTags = Set("dragons"),
            expectedAuthorUsername = "Author"
          )
      yield ()

    result

  test("delete article endpoint removes article for the author"):
    val httpApp = httpAppFixture()
    val registerPayload =
      """{"user":{"username":"Remover","email":"remover@example.com","password":"secretpass"}}"""
    val registerRequest = Request[IO](Method.POST, uri"/api/users")
      .withEntity(registerPayload)
      .withContentType(`Content-Type`(MediaType.application.json))

    val createPayload =
      """{"article":{"title":"Disposable article","description":"Temporary","body":"To be deleted","tagList":["temp"]}}"""

    val result =
      for
        registerResponse <- httpApp.run(registerRequest)
        _ = assertEquals(registerResponse.status, Status.Ok)
        (userResponse, _) <- assertUserPayload(registerResponse, "remover@example.com", "Remover")
        token = userResponse.user.token
        createRequest = Request[IO](Method.POST, uri"/api/articles")
          .putHeaders(authHeader(token))
          .withEntity(createPayload)
          .withContentType(`Content-Type`(MediaType.application.json))
        createResponse <- httpApp.run(createRequest)
        _ = assertEquals(createResponse.status, Status.Created)
        createdArticle <-
          assertArticlePayload(
            createResponse,
            expectedSlug = "disposable-article",
            expectedTitle = "Disposable article",
            expectedDescription = "Temporary",
            expectedBody = "To be deleted",
            expectedTags = Set("temp"),
            expectedAuthorUsername = "Remover"
          )
        deleteRequest = Request[IO](
          Method.DELETE,
          uri"/api/articles" / createdArticle.article.slug
        ).putHeaders(authHeader(token))
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

    val authorRequest = Request[IO](Method.POST, uri"/api/users")
      .withEntity(authorPayload)
      .withContentType(`Content-Type`(MediaType.application.json))

    val readerRequest = Request[IO](Method.POST, uri"/api/users")
      .withEntity(readerPayload)
      .withContentType(`Content-Type`(MediaType.application.json))

    val result =
      for
        authorResponse <- httpApp.run(authorRequest)
        _ = assertEquals(authorResponse.status, Status.Ok)
        (authorUserResponse, _) <-
          assertUserPayload(authorResponse, "author-fav@example.com", "FavAuthor")
        authorToken = authorUserResponse.user.token
        createRequest = Request[IO](Method.POST, uri"/api/articles")
          .putHeaders(authHeader(authorToken))
          .withEntity(articlePayload)
          .withContentType(`Content-Type`(MediaType.application.json))
        createResponse <- httpApp.run(createRequest)
        _ = assertEquals(createResponse.status, Status.Created)
        createdArticle <-
          assertArticlePayload(
            createResponse,
            expectedSlug = "favorite-me",
            expectedTitle = "Favorite me",
            expectedDescription = "Please do",
            expectedBody = "Pretty please",
            expectedTags = Set("tag"),
            expectedAuthorUsername = "FavAuthor"
          )
        readerResponse <- httpApp.run(readerRequest)
        _ = assertEquals(readerResponse.status, Status.Ok)
        (readerUserResponse, _) <-
          assertUserPayload(readerResponse, "reader@example.com", "FavReader")
        readerToken = readerUserResponse.user.token
        favoriteRequest = Request[IO](
          Method.POST,
          uri"/api/articles" / createdArticle.article.slug / "favorite"
        ).putHeaders(authHeader(readerToken))
        favoriteResponse <- httpApp.run(favoriteRequest)
        _ = assertEquals(favoriteResponse.status, Status.Ok)
        _ <-
          assertArticlePayload(
            favoriteResponse,
            expectedSlug = createdArticle.article.slug,
            expectedTitle = "Favorite me",
            expectedDescription = "Please do",
            expectedBody = "Pretty please",
            expectedTags = Set("tag"),
            expectedAuthorUsername = "FavAuthor",
            expectedFavorited = true,
            expectedFavoritesCount = 1
          )
        getFavoritedRequest = Request[IO](
          Method.GET,
          uri"/api/articles" / createdArticle.article.slug
        ).putHeaders(authHeader(readerToken))
        getFavoritedResponse <- httpApp.run(getFavoritedRequest)
        _ = assertEquals(getFavoritedResponse.status, Status.Ok)
        _ <-
          assertArticlePayload(
            getFavoritedResponse,
            expectedSlug = createdArticle.article.slug,
            expectedTitle = "Favorite me",
            expectedDescription = "Please do",
            expectedBody = "Pretty please",
            expectedTags = Set("tag"),
            expectedAuthorUsername = "FavAuthor",
            expectedFavorited = true,
            expectedFavoritesCount = 1
          )
        unfavoriteRequest = Request[IO](
          Method.DELETE,
          uri"/api/articles" / createdArticle.article.slug / "favorite"
        ).putHeaders(authHeader(readerToken))
        unfavoriteResponse <- httpApp.run(unfavoriteRequest)
        _ = assertEquals(unfavoriteResponse.status, Status.Ok)
        _ <-
          assertArticlePayload(
            unfavoriteResponse,
            expectedSlug = createdArticle.article.slug,
            expectedTitle = "Favorite me",
            expectedDescription = "Please do",
            expectedBody = "Pretty please",
            expectedTags = Set("tag"),
            expectedAuthorUsername = "FavAuthor",
            expectedFavorited = false,
            expectedFavoritesCount = 0
          )
      yield ()

    result

  test("feed endpoint returns only articles from followed users in reverse chronological order"):
    val httpApp = httpAppFixture()

    val firstFollowedPayload =
      """{"article":{"title":"Followed article","description":"Latest news","body":"Hot off the press","tagList":["news"]}}"""
    val secondFollowedPayload =
      """{"article":{"title":"Another followed story","description":"Breaking","body":"More updates","tagList":["updates"]}}"""
    val otherPayload =
      """{"article":{"title":"Unfollowed post","description":"Should not show","body":"Hidden","tagList":["misc"]}}"""

    val result =
      for
        (followerResponse, _) <-
          registerUser(httpApp, "FeedReader", "feed.reader@example.com", "secret")
        (followedResponse, _) <-
          registerUser(httpApp, "FeedAuthor", "feed.author@example.com", "secret")
        (otherAuthorResponse, _) <-
          registerUser(httpApp, "OtherAuthor", "other.author@example.com", "secret")
        firstFollowedResponse <-
          httpApp.run(
            Request[IO](Method.POST, uri"/api/articles")
              .putHeaders(authHeader(followedResponse.user.token))
              .withEntity(firstFollowedPayload)
              .withContentType(`Content-Type`(MediaType.application.json))
          )
        _ = assertEquals(firstFollowedResponse.status, Status.Created)
        firstFollowedArticle <-
          assertArticlePayload(
            firstFollowedResponse,
            expectedSlug = "followed-article",
            expectedTitle = "Followed article",
            expectedDescription = "Latest news",
            expectedBody = "Hot off the press",
            expectedTags = Set("news"),
            expectedAuthorUsername = "FeedAuthor"
          )
        secondFollowedResponse <-
          httpApp.run(
            Request[IO](Method.POST, uri"/api/articles")
              .putHeaders(authHeader(followedResponse.user.token))
              .withEntity(secondFollowedPayload)
              .withContentType(`Content-Type`(MediaType.application.json))
          )
        _ = assertEquals(secondFollowedResponse.status, Status.Created)
        secondFollowedArticle <-
          assertArticlePayload(
            secondFollowedResponse,
            expectedSlug = "another-followed-story",
            expectedTitle = "Another followed story",
            expectedDescription = "Breaking",
            expectedBody = "More updates",
            expectedTags = Set("updates"),
            expectedAuthorUsername = "FeedAuthor"
          )
        otherArticleResponse <-
          httpApp.run(
            Request[IO](Method.POST, uri"/api/articles")
              .putHeaders(authHeader(otherAuthorResponse.user.token))
              .withEntity(otherPayload)
              .withContentType(`Content-Type`(MediaType.application.json))
          )
        _ = assertEquals(otherArticleResponse.status, Status.Created)
        otherArticle <- assertArticlePayload(
          otherArticleResponse,
          expectedSlug = "unfollowed-post",
          expectedTitle = "Unfollowed post",
          expectedDescription = "Should not show",
          expectedBody = "Hidden",
          expectedTags = Set("misc"),
          expectedAuthorUsername = "OtherAuthor"
        )
        initialFeedRequest = Request[IO](
          Method.GET,
          uri"/api/articles/feed"
        ).putHeaders(authHeader(followerResponse.user.token))
        initialFeedResponse <- httpApp.run(initialFeedRequest)
        _ = assertEquals(initialFeedResponse.status, Status.Ok)
        initialFeed <- initialFeedResponse
          .as[String]
          .map(readFromString[MultipleArticlesResponse](_))
        _ = assertEquals(initialFeed.articlesCount, 0)
        _ = assertEquals(initialFeed.articles, Nil)
        followResponse <-
          httpApp.run(
            Request[IO](
              Method.POST,
              uri"/api/profiles/FeedAuthor/follow"
            ).putHeaders(authHeader(followerResponse.user.token))
          )
        _ = assertEquals(followResponse.status, Status.Ok)
        feedPage1Response <- httpApp.run(
          Request[IO](
            Method.GET,
            uri"/api/articles/feed?limit=1"
          ).putHeaders(authHeader(followerResponse.user.token))
        )
        _ = assertEquals(feedPage1Response.status, Status.Ok)
        feedPage1 <- feedPage1Response.as[String].map(readFromString[MultipleArticlesResponse](_))
        _ = assertEquals(feedPage1.articlesCount, 2)
        _ = assertEquals(feedPage1.articles.map(_.slug), List(secondFollowedArticle.article.slug))
        _ = assert(feedPage1.articles.forall(_.author.following))
        feedPage2Response <-
          httpApp.run(
            Request[IO](
              Method.GET,
              uri"/api/articles/feed?limit=1&offset=1"
            ).putHeaders(authHeader(followerResponse.user.token))
          )
        _ = assertEquals(feedPage2Response.status, Status.Ok)
        feedPage2 <- feedPage2Response.as[String].map(readFromString[MultipleArticlesResponse](_))
        _ = assertEquals(feedPage2.articlesCount, 2)
        _ = assertEquals(feedPage2.articles.map(_.slug), List(firstFollowedArticle.article.slug))
        _ = assert(feedPage2.articles.forall(_.author.following))
        _ = assert(!feedPage2.articles.exists(_.slug == otherArticle.article.slug))
      yield ()

    result

  test("comments endpoints allow creating, listing, and deleting comments"):
    val httpApp = httpAppFixture()
    val articlePayload =
      """{"article":{"title":"Article with comments","description":"Comment playground","body":"Test comments","tagList":["discussion"]}}"""
    val firstCommentPayload = """{"comment":{"body":"Nice writeup"}}"""
    val secondCommentPayload = """{"comment":{"body":"Thanks for sharing"}}"""

    val result =
      for
        (authorResponse, _) <-
          registerUser(httpApp, "CommentAuthor", "comment.author@example.com", "secret")
        createArticleResponse <-
          httpApp.run(
            Request[IO](Method.POST, uri"/api/articles")
              .putHeaders(authHeader(authorResponse.user.token))
              .withEntity(articlePayload)
              .withContentType(`Content-Type`(MediaType.application.json))
          )
        _ = assertEquals(createArticleResponse.status, Status.Created)
        createdArticle <-
          assertArticlePayload(
            createArticleResponse,
            expectedSlug = "article-with-comments",
            expectedTitle = "Article with comments",
            expectedDescription = "Comment playground",
            expectedBody = "Test comments",
            expectedTags = Set("discussion"),
            expectedAuthorUsername = "CommentAuthor"
          )
        (commenterResponse, _) <-
          registerUser(httpApp, "Commenter", "commenter@example.com", "secret")
        firstCommentResponse <-
          httpApp.run(
            Request[IO](Method.POST, uri"/api/articles" / createdArticle.article.slug / "comments")
              .putHeaders(authHeader(commenterResponse.user.token))
              .withEntity(firstCommentPayload)
              .withContentType(`Content-Type`(MediaType.application.json))
          )
        _ = assertEquals(firstCommentResponse.status, Status.Ok)
        firstComment <- assertCommentPayload(firstCommentResponse, "Nice writeup", "Commenter")
        secondCommentResponse <-
          httpApp.run(
            Request[IO](Method.POST, uri"/api/articles" / createdArticle.article.slug / "comments")
              .putHeaders(authHeader(commenterResponse.user.token))
              .withEntity(secondCommentPayload)
              .withContentType(`Content-Type`(MediaType.application.json))
          )
        _ = assertEquals(secondCommentResponse.status, Status.Ok)
        secondComment <- assertCommentPayload(
          secondCommentResponse,
          "Thanks for sharing",
          "Commenter"
        )
        anonymousListResponse <-
          httpApp.run(
            Request[IO](Method.GET, uri"/api/articles" / createdArticle.article.slug / "comments")
          )
        _ = assertEquals(anonymousListResponse.status, Status.Ok)
        anonymousComments <- anonymousListResponse
          .as[String]
          .map(readFromString[MultipleCommentsResponse](_))
        _ = assertEquals(
          anonymousComments.comments.map(_.id),
          List(secondComment.comment.id, firstComment.comment.id)
        )
        _ = assert(anonymousComments.comments.forall(!_.author.following))
        (readerResponse, _) <-
          registerUser(httpApp, "CommentReader", "comment.reader@example.com", "secret")
        followResponse <-
          httpApp.run(
            Request[IO](
              Method.POST,
              uri"/api/profiles/Commenter/follow"
            ).putHeaders(authHeader(readerResponse.user.token))
          )
        _ = assertEquals(followResponse.status, Status.Ok)
        authedListResponse <-
          httpApp.run(
            Request[IO](
              Method.GET,
              uri"/api/articles" / createdArticle.article.slug / "comments"
            ).putHeaders(authHeader(readerResponse.user.token))
          )
        _ = assertEquals(authedListResponse.status, Status.Ok)
        authedComments <- authedListResponse
          .as[String]
          .map(readFromString[MultipleCommentsResponse](_))
        _ = assert(authedComments.comments.forall(_.author.following))
        deleteFirstCommentRequest = Request[IO](
          Method.DELETE,
          uri"/api/articles" / createdArticle.article.slug / "comments" / CommentId
            .value(firstComment.comment.id)
            .toString
        ).putHeaders(authHeader(commenterResponse.user.token))
        deleteFirstCommentResponse <- httpApp.run(deleteFirstCommentRequest)
        _ = assertEquals(deleteFirstCommentResponse.status, Status.NoContent)
        afterDeleteResponse <-
          httpApp.run(
            Request[IO](Method.GET, uri"/api/articles" / createdArticle.article.slug / "comments")
          )
        _ = assertEquals(afterDeleteResponse.status, Status.Ok)
        afterDelete <- afterDeleteResponse
          .as[String]
          .map(readFromString[MultipleCommentsResponse](_))
        _ = assertEquals(afterDelete.comments.map(_.id), List(secondComment.comment.id))
      yield ()

    result

  test("profile endpoint returns user profile without authentication"):
    val httpApp = httpAppFixture()
    val registerPayload =
      """{"user":{"username":"Mallory","email":"mallory@example.com","password":"password"}}"""
    val registerRequest = Request[IO](Method.POST, uri"/api/users")
      .withEntity(registerPayload)
      .withContentType(`Content-Type`(MediaType.application.json))

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

    val followerRequest = Request[IO](Method.POST, uri"/api/users")
      .withEntity(followerPayload)
      .withContentType(`Content-Type`(MediaType.application.json))

    val followeeRequest = Request[IO](Method.POST, uri"/api/users")
      .withEntity(followeePayload)
      .withContentType(`Content-Type`(MediaType.application.json))

    val result =
      for
        followerResponse <- httpApp.run(followerRequest)
        _ = assertEquals(followerResponse.status, Status.Ok)
        (followerUserResponse, _) <-
          assertUserPayload(followerResponse, "follower@example.com", "Follower")
        followeeResponse <- httpApp.run(followeeRequest)
        _ = assertEquals(followeeResponse.status, Status.Ok)
        _ <- assertUserPayload(followeeResponse, "leader@example.com", "Leader")
        authHdr = authHeader(followerUserResponse.user.token)
        preFollowResponse <-
          httpApp.run(Request[IO](Method.GET, uri"/api/profiles/Leader").putHeaders(authHdr))
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
            Request[IO](Method.POST, uri"/api/profiles/Leader/follow").putHeaders(authHdr)
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
          httpApp.run(Request[IO](Method.GET, uri"/api/profiles/Leader").putHeaders(authHdr))
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
            Request[IO](Method.DELETE, uri"/api/profiles/Leader/follow").putHeaders(authHdr)
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
          httpApp.run(Request[IO](Method.GET, uri"/api/profiles/Leader").putHeaders(authHdr))
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

    val registerRequest = Request[IO](Method.POST, uri"/api/users")
      .withEntity(registerPayload)
      .withContentType(`Content-Type`(MediaType.application.json))

    val loginRequest = Request[IO](Method.POST, uri"/api/users/login")
      .withEntity(loginPayload)
      .withContentType(`Content-Type`(MediaType.application.json))

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

    val registerRequest = Request[IO](Method.POST, uri"/api/users")
      .withEntity(registerPayload)
      .withContentType(`Content-Type`(MediaType.application.json))

    httpApp
      .run(registerRequest)
      .flatMap { response =>
        assertEquals(response.status, Status.Ok)
        assertUserPayload(response, "carol@example.com", "Carol")
      }
      .flatMap { case (registeredUserResponse, registeredUserId) =>
        val token = registeredUserResponse.user.token
        val currentUserRequest = Request[IO](Method.GET, uri"/api/user")
          .putHeaders(authHeader(token))

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

    val registerRequest = Request[IO](Method.POST, uri"/api/users")
      .withEntity(registerPayload)
      .withContentType(`Content-Type`(MediaType.application.json))

    val result =
      for
        registerResponse <- httpApp.run(registerRequest)
        _ = assertEquals(registerResponse.status, Status.Ok)
        (registeredUserResponse, registeredUserId) <-
          assertUserPayload(registerResponse, "eve@example.com", "Eve")
        token = registeredUserResponse.user.token
        updateRequest = Request[IO](Method.PUT, uri"/api/user")
          .putHeaders(authHeader(token))
          .withEntity(updatePayload)
          .withContentType(`Content-Type`(MediaType.application.json))
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
        loginRequest = Request[IO](Method.POST, uri"/api/users/login")
          .withEntity(loginPayload)
          .withContentType(`Content-Type`(MediaType.application.json))
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
        profileRequest = Request[IO](Method.GET, uri"/api/profiles" / expectedUsername)
          .putHeaders(authHeader(token))
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

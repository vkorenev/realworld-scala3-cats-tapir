package com.example.realworld

import cats.ApplicativeThrow
import cats.effect.Async
import cats.syntax.all.*
import com.example.realworld.auth.AuthToken
import com.example.realworld.model.ArticleResponse
import com.example.realworld.model.CommentId
import com.example.realworld.model.CommentResponse
import com.example.realworld.model.LoginUserRequest
import com.example.realworld.model.MultipleArticlesResponse
import com.example.realworld.model.MultipleCommentsResponse
import com.example.realworld.model.NewArticleRequest
import com.example.realworld.model.NewCommentRequest
import com.example.realworld.model.NewUserRequest
import com.example.realworld.model.ProfileResponse
import com.example.realworld.model.TagsResponse
import com.example.realworld.model.UpdateArticleRequest
import com.example.realworld.model.UpdateUserRequest
import com.example.realworld.model.UserId
import com.example.realworld.model.UserResponse
import com.example.realworld.service.ArticleFilters
import com.example.realworld.service.ArticleService
import com.example.realworld.service.CommentService
import com.example.realworld.service.Pagination
import com.example.realworld.service.UserService
import org.http4s.HttpRoutes
import sttp.model.StatusCode
import sttp.model.headers.WWWAuthenticateChallenge
import sttp.tapir.*
import sttp.tapir.json.jsoniter.*
import sttp.tapir.server.http4s.Http4sServerInterpreter
import sttp.tapir.server.http4s.Http4sServerOptions
import sttp.tapir.server.interceptor.cors.CORSConfig
import sttp.tapir.server.interceptor.cors.CORSInterceptor
import sttp.tapir.swagger.bundle.SwaggerInterpreter

case class Endpoints[F[_]: Async](
    userService: UserService[F],
    articleService: ArticleService[F],
    commentService: CommentService[F],
    authToken: AuthToken[F]
):
  /** API specification uses non-standard authentication scheme */
  private val TokenAuthScheme = "Token"
  private val DefaultArticleLimit = 20

  private def normalizeLimit(limit: Int): Int =
    if limit <= 0 then DefaultArticleLimit
    else limit

  private def normalizeOffset(offset: Int): Int =
    if offset < 0 then 0
    else offset

  private def tokenAuth[T: Codec[List[String], *, CodecFormat.TextPlain]](
      challenge: WWWAuthenticateChallenge = WWWAuthenticateChallenge(TokenAuthScheme)
  ): EndpointInput.Auth[T, EndpointInput.AuthType.Http] =
    TapirAuth.http(TokenAuthScheme, challenge)

  private val secureEndpointErrorOutput = oneOf[Exception](
    oneOfVariant(statusCode(StatusCode.Unauthorized).and(jsonBody[Unauthorized]))
  )

  private val secureEndpoint = endpoint
    .securityIn(
      auth
        .bearer[Option[String]]()
        .and(tokenAuth[Option[String]]())
        .mapDecode {
          case (Some(token), _) => DecodeResult.Value(token)
          case (_, Some(token)) => DecodeResult.Value(token)
          case (None, None) => DecodeResult.Missing
        } { token =>
          (Some(token), None)
        }
    )
    .errorOut(secureEndpointErrorOutput)
    .serverSecurityLogicRecoverErrors[UserId, F](authToken.resolve)

  private val optionallySecureEndpoint = endpoint
    .securityIn(
      auth
        .bearer[Option[String]]()
        .and(tokenAuth[Option[String]]())
        .and(emptyAuth)
        .mapDecode {
          case (Some(token), _) => DecodeResult.Value(Some(token))
          case (_, Some(token)) => DecodeResult.Value(Some(token))
          case (None, None) => DecodeResult.Value(None)
        } {
          case Some(token) => (Some(token), None)
          case None => (None, None)
        }
    )
    .errorOut(secureEndpointErrorOutput)
    .serverSecurityLogicRecoverErrors[Option[UserId], F](_.traverse(authToken.resolve))

  private def livenessEndpoint = endpoint.get
    .in("__health" / "liveness")
    .out(statusCode(StatusCode.NoContent))
    .description("Liveness probe")
    .serverLogicPure[F](_ => Right(()))

  private def loginUserEndpoint = endpoint.post
    .in("api" / "users" / "login")
    .in(jsonBody[LoginUserRequest])
    .out(jsonBody[UserResponse])
    .errorOut(statusCode(StatusCode.Unauthorized))
    .description("Login for existing user")
    .tag("User and Authentication")
    .serverLogic[F] { request =>
      userService.authenticate(request.user.email, request.user.password).map {
        case Some(user) => Right(UserResponse(user))
        case None => Left(())
      }
    }

  private def registerUserEndpoint = endpoint.post
    .in("api" / "users")
    .in(jsonBody[NewUserRequest])
    .out(jsonBody[UserResponse])
    .description("Register a new user")
    .tag("User and Authentication")
    .serverLogicSuccess[F] { request =>
      userService
        .register(request.user)
        .map(UserResponse.apply)
    }

  private def currentUserEndpoint = secureEndpoint.get
    .in("api" / "user")
    .out(jsonBody[UserResponse])
    .description("Gets the currently logged-in user")
    .tag("User and Authentication")
    .serverLogicRecoverErrors { userId => _ =>
      userService.findById(userId).map(UserResponse.apply)
    }

  private def updateUserEndpoint = secureEndpoint.put
    .in("api" / "user")
    .in(jsonBody[UpdateUserRequest])
    .out(jsonBody[UserResponse])
    .description("Updated user information for current user")
    .tag("User and Authentication")
    .serverLogicRecoverErrors { userId => request =>
      userService.update(userId, request.user).map(UserResponse.apply)
    }

  private def profileEndpoint = optionallySecureEndpoint.get
    .in("api" / "profiles" / path[String]("username"))
    .out(jsonBody[ProfileResponse])
    .description("Get a profile of a user of the system. Auth is optional")
    .tag("Profile")
    .errorOutVariantPrepend(oneOfVariant(statusCode(StatusCode.NotFound).and(jsonBody[NotFound])))
    .serverLogicRecoverErrors { userId => username =>
      userService
        .findProfile(userId, username)
        .flatMap(ApplicativeThrow[F].fromOption(_, NotFound()).map(ProfileResponse.apply))
    }

  private def followProfileEndpoint = secureEndpoint.post
    .in("api" / "profiles" / path[String]("username") / "follow")
    .out(jsonBody[ProfileResponse])
    .description("Follow a user by username")
    .tag("Profile")
    .errorOutVariantPrepend(oneOfVariant(statusCode(StatusCode.NotFound).and(jsonBody[NotFound])))
    .serverLogicRecoverErrors { followerId => username =>
      userService.follow(followerId, username).map(ProfileResponse.apply)
    }

  private def unfollowProfileEndpoint = secureEndpoint.delete
    .in("api" / "profiles" / path[String]("username") / "follow")
    .out(jsonBody[ProfileResponse])
    .description("Unfollow a user by username")
    .tag("Profile")
    .errorOutVariantPrepend(oneOfVariant(statusCode(StatusCode.NotFound).and(jsonBody[NotFound])))
    .serverLogicRecoverErrors { followerId => username =>
      userService.unfollow(followerId, username).map(ProfileResponse.apply)
    }

  private def feedArticlesEndpoint = secureEndpoint.get
    .in("api" / "articles" / "feed")
    .in(query[Int]("limit").default(DefaultArticleLimit))
    .in(query[Int]("offset").default(0))
    .out(jsonBody[MultipleArticlesResponse])
    .description("Get most recent articles from users you follow")
    .tag("Articles")
    .serverLogicRecoverErrors { userId => params =>
      val (limit, offset) = params
      val pagination = Pagination(
        limit = normalizeLimit(limit),
        offset = normalizeOffset(offset)
      )
      articleService.feed(userId, pagination)
    }

  private def listArticlesEndpoint = optionallySecureEndpoint.get
    .in("api" / "articles")
    .in(query[Option[String]]("tag"))
    .in(query[Option[String]]("author"))
    .in(query[Option[String]]("favorited"))
    .in(query[Int]("limit").default(DefaultArticleLimit))
    .in(query[Int]("offset").default(0))
    .out(jsonBody[MultipleArticlesResponse])
    .description("Get most recent articles globally")
    .tag("Articles")
    .serverLogicRecoverErrors { userId => params =>
      val (tag, author, favorited, limit, offset) = params
      val filters = ArticleFilters(tag = tag, author = author, favorited = favorited)
      val pagination = Pagination(
        limit = normalizeLimit(limit),
        offset = normalizeOffset(offset)
      )
      articleService.list(userId, filters, pagination)
    }

  private def getArticleEndpoint = optionallySecureEndpoint.get
    .in("api" / "articles" / path[String]("slug"))
    .out(jsonBody[ArticleResponse])
    .description("Get an article")
    .tag("Articles")
    .errorOutVariantPrepend(oneOfVariant(statusCode(StatusCode.NotFound).and(jsonBody[NotFound])))
    .serverLogicRecoverErrors { userId => slug =>
      articleService
        .find(userId, slug)
        .flatMap(ApplicativeThrow[F].fromOption(_, NotFound()).map(ArticleResponse.apply))
    }

  private def createArticleEndpoint = secureEndpoint.post
    .in("api" / "articles")
    .in(jsonBody[NewArticleRequest])
    .out(jsonBody[ArticleResponse])
    .out(statusCode(StatusCode.Created))
    .description("Create an article")
    .tag("Articles")
    .serverLogicRecoverErrors { authorId => request =>
      articleService
        .create(authorId, request.article)
        .map(ArticleResponse.apply)
    }

  private def updateArticleEndpoint = secureEndpoint.put
    .in("api" / "articles" / path[String]("slug"))
    .in(jsonBody[UpdateArticleRequest])
    .out(jsonBody[ArticleResponse])
    .description("Update an article")
    .tag("Articles")
    .errorOutVariantPrepend(oneOfVariant(statusCode(StatusCode.NotFound).and(jsonBody[NotFound])))
    .serverLogicRecoverErrors { authorId => params =>
      val (slug, request) = params
      articleService
        .update(authorId, slug, request.article)
        .map(ArticleResponse.apply)
    }

  private def deleteArticleEndpoint = secureEndpoint.delete
    .in("api" / "articles" / path[String]("slug"))
    .out(statusCode(StatusCode.NoContent))
    .description("Delete an article")
    .tag("Articles")
    .errorOutVariantPrepend(oneOfVariant(statusCode(StatusCode.NotFound).and(jsonBody[NotFound])))
    .serverLogicRecoverErrors { authorId => slug =>
      articleService.delete(authorId, slug)
    }

  private def addCommentEndpoint = secureEndpoint.post
    .in("api" / "articles" / path[String]("slug") / "comments")
    .in(jsonBody[NewCommentRequest])
    .out(jsonBody[CommentResponse])
    .description("Create a comment for an article")
    .tag("Comments")
    .errorOutVariantPrepend(oneOfVariant(statusCode(StatusCode.NotFound).and(jsonBody[NotFound])))
    .serverLogicRecoverErrors { userId => params =>
      val (slug, request) = params
      commentService
        .add(userId, slug, request.comment.body)
        .map(CommentResponse.apply)
    }

  private def listCommentsEndpoint = optionallySecureEndpoint.get
    .in("api" / "articles" / path[String]("slug") / "comments")
    .out(jsonBody[MultipleCommentsResponse])
    .description("Get comments for an article")
    .tag("Comments")
    .errorOutVariantPrepend(oneOfVariant(statusCode(StatusCode.NotFound).and(jsonBody[NotFound])))
    .serverLogicRecoverErrors { userId => slug =>
      commentService.list(userId, slug)
    }

  private def deleteCommentEndpoint = secureEndpoint.delete
    .in("api" / "articles" / path[String]("slug") / "comments" / path[Long]("id"))
    .out(statusCode(StatusCode.NoContent))
    .description("Delete a comment")
    .tag("Comments")
    .errorOutVariantPrepend(oneOfVariant(statusCode(StatusCode.NotFound).and(jsonBody[NotFound])))
    .serverLogicRecoverErrors { userId => params =>
      val (slug, commentId) = params
      commentService.delete(userId, slug, CommentId(commentId))
    }

  private def favoriteArticleEndpoint = secureEndpoint.post
    .in("api" / "articles" / path[String]("slug") / "favorite")
    .out(jsonBody[ArticleResponse])
    .description("Favorite an article")
    .tag("Favorites")
    .errorOutVariantPrepend(oneOfVariant(statusCode(StatusCode.NotFound).and(jsonBody[NotFound])))
    .serverLogicRecoverErrors { userId => slug =>
      articleService.favorite(userId, slug).map(ArticleResponse.apply)
    }

  private def unfavoriteArticleEndpoint = secureEndpoint.delete
    .in("api" / "articles" / path[String]("slug") / "favorite")
    .out(jsonBody[ArticleResponse])
    .description("Unfavorite an article")
    .tag("Favorites")
    .errorOutVariantPrepend(oneOfVariant(statusCode(StatusCode.NotFound).and(jsonBody[NotFound])))
    .serverLogicRecoverErrors { userId => slug =>
      articleService.unfavorite(userId, slug).map(ArticleResponse.apply)
    }

  private def listTagsEndpoint = endpoint.get
    .in("api" / "tags")
    .out(jsonBody[TagsResponse])
    .description("Get all tags")
    .tag("Tags")
    .serverLogicSuccess[F](_ => articleService.listTags.map(TagsResponse.apply))

  def routes: HttpRoutes[F] =
    val serverEndpoints = List(
      livenessEndpoint,
      loginUserEndpoint,
      registerUserEndpoint,
      currentUserEndpoint,
      updateUserEndpoint,
      profileEndpoint,
      followProfileEndpoint,
      unfollowProfileEndpoint,
      feedArticlesEndpoint,
      listArticlesEndpoint,
      getArticleEndpoint,
      createArticleEndpoint,
      updateArticleEndpoint,
      deleteArticleEndpoint,
      addCommentEndpoint,
      listCommentsEndpoint,
      deleteCommentEndpoint,
      favoriteArticleEndpoint,
      unfavoriteArticleEndpoint,
      listTagsEndpoint
    )

    val swaggerEndpoints =
      SwaggerInterpreter().fromServerEndpoints[F](serverEndpoints, "RealWorld Conduit API", "1.0.0")

    val serverOptions = Http4sServerOptions.customiseInterceptors
      .corsInterceptor(CORSInterceptor.customOrThrow(CORSConfig.default.allowAllMethods))
      .options

    Http4sServerInterpreter[F](serverOptions).toRoutes(serverEndpoints ++ swaggerEndpoints)

package com.example.realworld.repository

import cats.ApplicativeThrow
import cats.effect.Async
import cats.syntax.all.*
import com.example.realworld.db.Queries
import com.example.realworld.model.ArticleId
import com.example.realworld.model.NewArticle
import com.example.realworld.model.UserId
import doobie.ConnectionIO
import doobie.Read
import doobie.Transactor
import doobie.implicits.*
import doobie.postgres.implicits.*

import java.time.Instant

trait ArticleRepository[F[_]]:
  def create(authorId: UserId, baseSlug: String, article: NewArticle, at: Instant): F[StoredArticle]
  def list(filters: ArticleFilters, pagination: Pagination): F[ArticlePage]
  def feed(userId: UserId, pagination: Pagination): F[ArticlePage]
  def findBySlug(slug: String): F[Option[StoredArticle]]

final case class StoredArticle(
    id: ArticleId,
    slug: String,
    title: String,
    description: String,
    body: String,
    tagList: List[String],
    createdAt: Instant,
    updatedAt: Instant,
    author: StoredUser
) derives Read

final case class ArticleFilters(
    tag: Option[String],
    author: Option[String],
    favorited: Option[String]
)

final case class Pagination(limit: Int, offset: Int)

final case class ArticlePage(articles: List[StoredArticle], articlesCount: Int)

final class DoobieArticleRepository[F[_]: Async](xa: Transactor[F]) extends ArticleRepository[F]:
  private def insertArticle(
      authorId: UserId,
      slug: String,
      article: NewArticle,
      at: Instant
  ): ConnectionIO[ArticleId] =
    Queries
      .insertArticle(slug, article, authorId, at)
      .withUniqueGeneratedKeys[ArticleId]("id")

  private def insertTags(articleId: ArticleId, tags: List[String]): ConnectionIO[Unit] =
    if tags.isEmpty then ().pure[ConnectionIO]
    else
      Queries.insertArticleTag
        .updateMany(tags.map(tag => (articleId, tag)))
        .void

  private def loadSlugsWithPrefix(baseSlug: String): ConnectionIO[List[String]] =
    val prefixPattern = s"$baseSlug%"
    Queries.selectSlugsWithPrefix(prefixPattern).to[List]

  private def ensureUniqueSlug(baseSlug: String): ConnectionIO[String] =
    loadSlugsWithPrefix(baseSlug).flatMap { existingSlugs =>
      ApplicativeThrow[ConnectionIO].fromOption(
        LazyList
          .from(0)
          .map {
            case 0 => baseSlug
            case n => s"$baseSlug-$n"
          }
          .find(candidate => !existingSlugs.contains(candidate)),
        new IllegalStateException(s"Unable to generate unique slug for '$baseSlug'")
      )
    }

  override def create(
      authorId: UserId,
      baseSlug: String,
      article: NewArticle,
      at: Instant
  ): F[StoredArticle] =
    val sanitizedTags =
      article.tagList
        .getOrElse(Nil)
        .map(_.trim)
        .filter(_.nonEmpty)
        .distinct

    (for
      author <- Queries.selectById(authorId).unique
      slug <- ensureUniqueSlug(baseSlug)
      articleId <- insertArticle(authorId, slug, article, at)
      _ <- insertTags(articleId, sanitizedTags)
    yield StoredArticle(
      id = articleId,
      slug = slug,
      title = article.title,
      description = article.description,
      body = article.body,
      tagList = sanitizedTags,
      createdAt = at,
      updatedAt = at,
      author = author
    )).transact(xa)

  private def selectArticlesQuery(
      filters: ArticleFilters,
      limit: Int,
      offset: Int
  ): ConnectionIO[List[StoredArticle]] =
    Queries
      .selectArticles(filters, limit, offset)
      .to[List]

  private def selectArticlesCount(filters: ArticleFilters): ConnectionIO[Int] =
    Queries
      .selectArticlesCount(filters)
      .unique
      .map(_.toInt)

  override def list(filters: ArticleFilters, pagination: Pagination): F[ArticlePage] =
    val normalizedLimit = pagination.limit.max(0)
    val normalizedOffset = pagination.offset.max(0)

    (for
      articles <- selectArticlesQuery(filters, normalizedLimit, normalizedOffset)
      total <- selectArticlesCount(filters)
    yield ArticlePage(
      articles = articles,
      articlesCount = total
    )).transact(xa)

  private def selectFeedArticles(
      userId: UserId,
      limit: Int,
      offset: Int
  ): ConnectionIO[List[StoredArticle]] =
    Queries
      .selectFeedArticles(userId, limit, offset)
      .to[List]

  private def selectFeedCount(userId: UserId): ConnectionIO[Int] =
    Queries
      .selectFeedCount(userId)
      .unique
      .map(_.toInt)

  override def feed(userId: UserId, pagination: Pagination): F[ArticlePage] =
    val normalizedLimit = pagination.limit.max(0)
    val normalizedOffset = pagination.offset.max(0)

    (for
      rows <- selectFeedArticles(userId, normalizedLimit, normalizedOffset)
      total <- selectFeedCount(userId)
    yield ArticlePage(
      articles = rows,
      articlesCount = total
    )).transact(xa)

  private def selectArticleBySlug(
      slug: String
  ): ConnectionIO[Option[StoredArticle]] =
    Queries
      .selectArticleBySlug(slug)
      .option

  override def findBySlug(slug: String): F[Option[StoredArticle]] =
    selectArticleBySlug(slug)
      .transact(xa)

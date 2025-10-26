package com.example.realworld.repository

import cats.ApplicativeThrow
import cats.effect.Async
import cats.syntax.all.*
import com.example.realworld.db.Queries
import com.example.realworld.db.Queries.ArticleRow
import com.example.realworld.model.ArticleId
import com.example.realworld.model.NewArticle
import com.example.realworld.model.UserId
import doobie.ConnectionIO
import doobie.Transactor
import doobie.implicits.*

import java.time.Instant

trait ArticleRepository[F[_]]:
  def create(authorId: UserId, baseSlug: String, article: NewArticle, at: Instant): F[StoredArticle]
  def list(filters: ArticleFilters, pagination: Pagination): F[ArticlePage]
  def feed(userId: UserId, pagination: Pagination): F[ArticlePage]

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
)

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
  ): ConnectionIO[ArticleRow] =
    Queries
      .insertArticle(slug, article, authorId, at)
      .withUniqueGeneratedKeys[ArticleRow](
        "id",
        "slug",
        "title",
        "description",
        "body",
        "created_at",
        "updated_at",
        "author_id"
      )

  private def insertTags(articleId: ArticleId, tags: List[String]): ConnectionIO[Unit] =
    if tags.isEmpty then ().pure[ConnectionIO]
    else
      Queries.insertArticleTag
        .updateMany(tags.map(tag => (articleId, tag)))
        .void

  private def selectAuthor(userId: UserId): ConnectionIO[StoredUser] =
    Queries.selectById(userId).unique

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
      slug <- ensureUniqueSlug(baseSlug)
      row <- insertArticle(authorId, slug, article, at)
      _ <- insertTags(row.id, sanitizedTags)
      author <- selectAuthor(row.authorId)
    yield StoredArticle(
      id = row.id,
      slug = row.slug,
      title = row.title,
      description = row.description,
      body = row.body,
      tagList = sanitizedTags,
      createdAt = row.createdAt,
      updatedAt = row.updatedAt,
      author = author
    )).transact(xa)

  private def selectArticlesQuery(
      filters: ArticleFilters,
      limit: Int,
      offset: Int
  ): ConnectionIO[List[(ArticleRow, StoredUser, List[String])]] =
    Queries
      .selectArticles(filters, limit, offset)
      .to[List]

  private def selectArticlesCount(filters: ArticleFilters): ConnectionIO[Int] =
    Queries
      .selectArticlesCount(filters)
      .unique
      .map(_.toInt)

  private def toStoredArticles(
      rows: List[(ArticleRow, StoredUser, List[String])]
  ): List[StoredArticle] =
    rows.map { case (row, author, tags) =>
      StoredArticle(
        id = row.id,
        slug = row.slug,
        title = row.title,
        description = row.description,
        body = row.body,
        tagList = tags,
        createdAt = row.createdAt,
        updatedAt = row.updatedAt,
        author = author
      )
    }

  override def list(filters: ArticleFilters, pagination: Pagination): F[ArticlePage] =
    val normalizedLimit = pagination.limit.max(0)
    val normalizedOffset = pagination.offset.max(0)

    (for
      rows <- selectArticlesQuery(filters, normalizedLimit, normalizedOffset)
      total <- selectArticlesCount(filters)
    yield ArticlePage(
      articles = toStoredArticles(rows),
      articlesCount = total
    )).transact(xa)

  private def selectFeedArticles(
      userId: UserId,
      limit: Int,
      offset: Int
  ): ConnectionIO[List[(ArticleRow, StoredUser, List[String])]] =
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
      articles = toStoredArticles(rows),
      articlesCount = total
    )).transact(xa)

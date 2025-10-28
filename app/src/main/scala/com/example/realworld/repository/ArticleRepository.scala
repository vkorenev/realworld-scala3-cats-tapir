package com.example.realworld.repository

import cats.ApplicativeThrow
import cats.effect.Async
import cats.syntax.all.*
import com.example.realworld.NotFound
import com.example.realworld.Unauthorized
import com.example.realworld.db.Queries
import com.example.realworld.model.ArticleId
import com.example.realworld.model.NewArticle
import com.example.realworld.model.UpdateArticle
import com.example.realworld.model.UserId
import doobie.ConnectionIO
import doobie.Read
import doobie.Transactor
import doobie.implicits.*
import doobie.postgres.implicits.*

import java.text.Normalizer
import java.time.Instant
import java.util.Locale

trait ArticleRepository[F[_]]:
  def create(authorId: UserId, article: NewArticle, at: Instant): F[StoredArticle]
  def list(filters: ArticleFilters, pagination: Pagination): F[ArticlePage]
  def feed(userId: UserId, pagination: Pagination): F[ArticlePage]
  def findBySlug(slug: String): F[Option[StoredArticle]]
  def update(
      authorId: UserId,
      slug: String,
      update: UpdateArticle,
      updatedAt: Instant
  ): F[StoredArticle]
  def delete(authorId: UserId, slug: String): F[Unit]

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
    Queries.selectArticleSlugsWithPrefix(prefixPattern).to[List]

  private val DefaultSlug = "article"

  private def slugify(title: String): String =
    val normalized = Normalizer.normalize(title, Normalizer.Form.NFD)
    val ascii = normalized.replaceAll("\\p{M}", "")
    val lowerCased = ascii.toLowerCase(Locale.ENGLISH)
    val replaced = lowerCased.replaceAll("[^a-z0-9]+", "-")
    val trimmed = replaced.replaceAll("^-+|-+$", "")
    if trimmed.nonEmpty then trimmed else DefaultSlug

  private def ensureUniqueSlug(
      baseSlug: String,
      excludeSlug: Option[String]
  ): ConnectionIO[String] =
    loadSlugsWithPrefix(baseSlug).flatMap { existingSlugs =>
      val filtered = excludeSlug match
        case Some(slugToIgnore) => existingSlugs.filterNot(_ == slugToIgnore)
        case None => existingSlugs
      ApplicativeThrow[ConnectionIO].fromOption(
        LazyList
          .from(0)
          .map {
            case 0 => baseSlug
            case n => s"$baseSlug-$n"
          }
          .find(candidate => !filtered.contains(candidate)),
        new IllegalStateException(s"Unable to generate unique slug for '$baseSlug'")
      )
    }

  override def create(
      authorId: UserId,
      article: NewArticle,
      at: Instant
  ): F[StoredArticle] =
    val baseSlug = slugify(article.title)
    val sanitizedTags =
      article.tagList
        .getOrElse(Nil)
        .map(_.trim)
        .filter(_.nonEmpty)
        .distinct

    (for
      author <- Queries.selectUserById(authorId).unique
      slug <- ensureUniqueSlug(baseSlug, excludeSlug = None)
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

  override def update(
      authorId: UserId,
      slug: String,
      update: UpdateArticle,
      updatedAt: Instant
  ): F[StoredArticle] =
    (for
      existingOpt <- selectArticleBySlug(slug)
      existing <- ApplicativeThrow[ConnectionIO].fromOption(existingOpt, NotFound())
      _ <- ApplicativeThrow[ConnectionIO].raiseWhen(existing.author.id != authorId)(Unauthorized())
      newTitle = update.title.getOrElse(existing.title)
      newDescription = update.description.getOrElse(existing.description)
      newBody = update.body.getOrElse(existing.body)
      newSlug <-
        if update.title.isDefined then
          val baseSlug = slugify(newTitle)
          if baseSlug == existing.slug then existing.slug.pure[ConnectionIO]
          else ensureUniqueSlug(baseSlug, excludeSlug = existing.slug.some)
        else existing.slug.pure[ConnectionIO]
      _ <-
        Queries
          .updateArticle(
            id = existing.id,
            slug = newSlug,
            title = newTitle,
            description = newDescription,
            body = newBody,
            updatedAt = updatedAt
          )
          .run
          .void
    yield existing.copy(
      slug = newSlug,
      title = newTitle,
      description = newDescription,
      body = newBody,
      updatedAt = updatedAt
    )).transact(xa)

  override def delete(authorId: UserId, slug: String): F[Unit] =
    (for
      existingOpt <- selectArticleBySlug(slug)
      existing <- ApplicativeThrow[ConnectionIO].fromOption(existingOpt, NotFound())
      _ <- ApplicativeThrow[ConnectionIO].raiseWhen(existing.author.id != authorId)(Unauthorized())
      _ <- Queries.deleteArticle(existing.id).run.void
    yield ()).transact(xa)

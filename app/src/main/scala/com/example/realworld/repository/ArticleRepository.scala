package com.example.realworld.repository

import cats.Applicative
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
  def list(
      viewerId: Option[UserId],
      filters: ArticleFilters,
      pagination: Pagination
  ): F[ArticlePage]
  def feed(userId: UserId, pagination: Pagination): F[ArticlePage]
  def findBySlug(slug: String, viewerId: Option[UserId]): F[Option[StoredArticle]]
  def update(
      authorId: UserId,
      slug: String,
      update: UpdateArticle,
      updatedAt: Instant
  ): F[StoredArticle]
  def favorite(userId: UserId, slug: String): F[StoredArticle]
  def unfavorite(userId: UserId, slug: String): F[StoredArticle]
  def delete(authorId: UserId, slug: String): F[Unit]

final case class StoredArticle(
    id: ArticleId,
    slug: String,
    title: String,
    description: String,
    body: String,
    tagList: List[String],
    favorited: Boolean,
    favoritesCount: Int,
    createdAt: Instant,
    updatedAt: Instant,
    author: StoredProfile
) derives Read

final case class ArticleFilters(
    tag: Option[String],
    author: Option[String],
    favorited: Option[String]
)

final case class Pagination(limit: Int, offset: Int)

final case class ArticlePage(articles: List[StoredArticle], articlesCount: Int)

final class DoobieArticleRepository[F[_]: Async](xa: Transactor[F]) extends ArticleRepository[F]:
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
    Queries.selectArticleSlugsLike(s"$baseSlug%").to[List].flatMap { existingSlugs =>
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
      authorUser <- Queries.selectUserById(authorId).unique
      slug <- ensureUniqueSlug(baseSlug, excludeSlug = None)
      articleId <- Queries
        .insertArticle(slug, article, authorId, at)
        .withUniqueGeneratedKeys[ArticleId]("id")
      _ <- Applicative[ConnectionIO].unlessA(sanitizedTags.isEmpty) {
        Queries.insertArticleTag.updateMany(sanitizedTags.map(tag => (articleId, tag)))
      }
    yield StoredArticle(
      id = articleId,
      slug = slug,
      title = article.title,
      description = article.description,
      body = article.body,
      tagList = sanitizedTags,
      favorited = false,
      favoritesCount = 0,
      createdAt = at,
      updatedAt = at,
      author = StoredProfile(authorUser, following = false)
    )).transact(xa)

  override def list(
      viewerId: Option[UserId],
      filters: ArticleFilters,
      pagination: Pagination
  ): F[ArticlePage] =
    val normalizedLimit = pagination.limit.max(0)
    val normalizedOffset = pagination.offset.max(0)

    (for
      articles <- Queries
        .selectArticles(filters, viewerId, normalizedLimit, normalizedOffset)
        .to[List]
      total <- Queries.selectArticlesCount(filters).unique
    yield ArticlePage(
      articles = articles,
      articlesCount = total.toInt
    )).transact(xa)

  override def feed(userId: UserId, pagination: Pagination): F[ArticlePage] =
    val normalizedLimit = pagination.limit.max(0)
    val normalizedOffset = pagination.offset.max(0)

    (for
      rows <- Queries.selectFeedArticles(userId, normalizedLimit, normalizedOffset).to[List]
      total <- Queries.selectFeedCount(userId).unique
    yield ArticlePage(
      articles = rows,
      articlesCount = total.toInt
    )).transact(xa)

  override def findBySlug(slug: String, viewerId: Option[UserId]): F[Option[StoredArticle]] =
    Queries.selectArticleBySlug(slug, viewerId).option.transact(xa)

  override def favorite(userId: UserId, slug: String): F[StoredArticle] =
    (for
      existingOpt <- Queries.selectArticleBySlug(slug, viewerId = Some(userId)).option
      existing <- ApplicativeThrow[ConnectionIO].fromOption(existingOpt, NotFound())
      _ <- Queries.insertArticleFavorite(existing.id, userId).run.void
      updated <- Queries.selectArticleBySlug(slug, viewerId = Some(userId)).unique
    yield updated).transact(xa)

  override def unfavorite(userId: UserId, slug: String): F[StoredArticle] =
    (for
      existingOpt <- Queries.selectArticleBySlug(slug, viewerId = Some(userId)).option
      existing <- ApplicativeThrow[ConnectionIO].fromOption(existingOpt, NotFound())
      _ <- Queries.deleteArticleFavorite(existing.id, userId).run.void
      updated <- Queries.selectArticleBySlug(slug, viewerId = Some(userId)).unique
    yield updated).transact(xa)

  override def update(
      authorId: UserId,
      slug: String,
      update: UpdateArticle,
      updatedAt: Instant
  ): F[StoredArticle] =
    (for
      existingOpt <- Queries.selectArticleBySlug(slug, viewerId = Some(authorId)).option
      existing <- ApplicativeThrow[ConnectionIO].fromOption(existingOpt, NotFound())
      _ <- ApplicativeThrow[ConnectionIO].raiseWhen(existing.author.user.id != authorId)(
        Unauthorized()
      )
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
      refreshed <- Queries.selectArticleBySlug(newSlug, viewerId = Some(authorId)).unique
    yield refreshed).transact(xa)

  override def delete(authorId: UserId, slug: String): F[Unit] =
    (for
      existingOpt <- Queries.selectArticleBySlug(slug, viewerId = None).option
      existing <- ApplicativeThrow[ConnectionIO].fromOption(existingOpt, NotFound())
      _ <- ApplicativeThrow[ConnectionIO].raiseWhen(existing.author.user.id != authorId)(
        Unauthorized()
      )
      _ <- Queries.deleteArticle(existing.id).run.void
    yield ()).transact(xa)

package com.example.realworld.service

import cats.Applicative
import cats.ApplicativeThrow
import cats.effect.Async
import cats.syntax.all.*
import com.example.realworld.NotFound
import com.example.realworld.Unauthorized
import com.example.realworld.db.Queries
import com.example.realworld.db.StoredArticle
import com.example.realworld.db.StoredProfile
import com.example.realworld.model.Article
import com.example.realworld.model.ArticleId
import com.example.realworld.model.MultipleArticlesResponse
import com.example.realworld.model.NewArticle
import com.example.realworld.model.UpdateArticle
import com.example.realworld.model.UserId
import doobie.ConnectionIO
import doobie.Transactor
import doobie.implicits.*

import java.text.Normalizer
import java.util.Locale

final case class ArticleFilters(
    tag: Option[String],
    author: Option[String],
    favorited: Option[String]
)

final case class Pagination(limit: Int, offset: Int)

trait ArticleService[F[_]]:
  def create(authorId: UserId, article: NewArticle): F[Article]
  def list(
      viewerId: Option[UserId],
      filters: ArticleFilters,
      pagination: Pagination
  ): F[MultipleArticlesResponse]
  def feed(userId: UserId, pagination: Pagination): F[MultipleArticlesResponse]
  def find(viewerId: Option[UserId], slug: String): F[Option[Article]]
  def update(authorId: UserId, slug: String, update: UpdateArticle): F[Article]
  def favorite(userId: UserId, slug: String): F[Article]
  def unfavorite(userId: UserId, slug: String): F[Article]
  def delete(authorId: UserId, slug: String): F[Unit]
  def listTags: F[List[String]]

final class LiveArticleService[F[_]: Async](xa: Transactor[F]) extends ArticleService[F]:
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

  override def create(authorId: UserId, article: NewArticle): F[Article] =
    for
      at <- Async[F].realTimeInstant
      stored <- {
        val baseSlug = slugify(article.title)
        val sanitizedTags = article.tagList
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
      }
    yield stored.toArticle

  override def list(
      viewerId: Option[UserId],
      filters: ArticleFilters,
      pagination: Pagination
  ): F[MultipleArticlesResponse] =
    val normalizedLimit = pagination.limit.max(0)
    val normalizedOffset = pagination.offset.max(0)

    (for
      articles <- Queries
        .selectArticles(filters, viewerId, normalizedLimit, normalizedOffset)
        .to[List]
      total <- Queries.selectArticlesCount(filters).unique
    yield MultipleArticlesResponse(articles.map(_.toSummary), total.toInt)).transact(xa)

  override def feed(userId: UserId, pagination: Pagination): F[MultipleArticlesResponse] =
    val normalizedLimit = pagination.limit.max(0)
    val normalizedOffset = pagination.offset.max(0)

    (for
      articles <- Queries.selectFeedArticles(userId, normalizedLimit, normalizedOffset).to[List]
      total <- Queries.selectFeedCount(userId).unique
    yield MultipleArticlesResponse(articles.map(_.toSummary), total.toInt)).transact(xa)

  override def find(viewerId: Option[UserId], slug: String): F[Option[Article]] =
    Queries
      .selectArticleBySlug(slug, viewerId)
      .option
      .transact(xa)
      .map(_.map(_.toArticle))

  override def update(authorId: UserId, slug: String, update: UpdateArticle): F[Article] =
    for
      now <- Async[F].realTimeInstant
      article <- (for
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
        _ <- Queries
          .updateArticle(
            id = existing.id,
            slug = newSlug,
            title = newTitle,
            description = newDescription,
            body = newBody,
            updatedAt = now
          )
          .run
          .void
        refreshed <- Queries.selectArticleBySlug(newSlug, viewerId = Some(authorId)).unique
      yield refreshed.toArticle).transact(xa)
    yield article

  override def favorite(userId: UserId, slug: String): F[Article] =
    (for
      existing <- Queries.selectArticleBySlug(slug, viewerId = Some(userId)).unique
      _ <- Queries.insertArticleFavorite(existing.id, userId).run
      updated <- Queries.selectArticleBySlug(slug, viewerId = Some(userId)).unique
    yield updated.toArticle).transact(xa)

  override def unfavorite(userId: UserId, slug: String): F[Article] =
    (for
      existing <- Queries.selectArticleBySlug(slug, viewerId = Some(userId)).unique
      _ <- Queries.deleteArticleFavorite(existing.id, userId).run
      updated <- Queries.selectArticleBySlug(slug, viewerId = Some(userId)).unique
    yield updated.toArticle).transact(xa)

  override def delete(authorId: UserId, slug: String): F[Unit] =
    (for
      existingOpt <- Queries.selectArticleBySlug(slug, viewerId = None).option
      existing <- ApplicativeThrow[ConnectionIO].fromOption(existingOpt, NotFound())
      _ <- ApplicativeThrow[ConnectionIO].raiseWhen(existing.author.user.id != authorId)(
        Unauthorized()
      )
      _ <- Queries.deleteArticle(existing.id).run.void
    yield ()).transact(xa)

  override def listTags: F[List[String]] =
    Queries.selectTags.to[List].transact(xa)

object ArticleService:
  def live[F[_]: Async](xa: Transactor[F]): ArticleService[F] = LiveArticleService(xa)

  def apply[F[_]: Async](xa: Transactor[F]): ArticleService[F] = live(xa)

package com.example.realworld.service

import cats.effect.Async
import cats.syntax.all.*
import com.example.realworld.model.Article
import com.example.realworld.model.ArticleSummary
import com.example.realworld.model.MultipleArticlesResponse
import com.example.realworld.model.NewArticle
import com.example.realworld.model.Profile
import com.example.realworld.model.UserId
import com.example.realworld.repository.ArticleFilters
import com.example.realworld.repository.ArticlePage
import com.example.realworld.repository.ArticleRepository
import com.example.realworld.repository.Pagination
import com.example.realworld.repository.StoredArticle

import java.text.Normalizer
import java.util.Locale

trait ArticleService[F[_]]:
  def create(authorId: UserId, article: NewArticle): F[Article]
  def list(
      viewerId: Option[UserId],
      filters: ArticleFilters,
      pagination: Pagination
  ): F[MultipleArticlesResponse]
  def feed(userId: UserId, pagination: Pagination): F[MultipleArticlesResponse]

final class LiveArticleService[F[_]: Async](articleRepository: ArticleRepository[F])
    extends ArticleService[F]:
  private val DefaultSlug = "article"

  private def slugify(title: String): String =
    val normalized = Normalizer.normalize(title, Normalizer.Form.NFD)
    val ascii = normalized.replaceAll("\\p{M}", "")
    val lowerCased = ascii.toLowerCase(Locale.ENGLISH)
    val replaced = lowerCased.replaceAll("[^a-z0-9]+", "-")
    val trimmed = replaced.replaceAll("^-+|-+$", "")
    if trimmed.nonEmpty then trimmed else DefaultSlug

  private def toArticle(
      stored: StoredArticle,
      favorited: Boolean,
      favoritesCount: Int,
      following: Boolean
  ): Article =
    Article(
      slug = stored.slug,
      title = stored.title,
      description = stored.description,
      body = stored.body,
      tagList = stored.tagList,
      createdAt = stored.createdAt,
      updatedAt = stored.updatedAt,
      favorited = favorited,
      favoritesCount = favoritesCount,
      author = Profile(
        username = stored.author.username,
        bio = stored.author.bio,
        image = stored.author.image,
        following = following
      )
    )

  override def create(authorId: UserId, article: NewArticle): F[Article] =
    val baseSlug = slugify(article.title)
    for
      at <- Async[F].realTimeInstant
      article <- articleRepository
        .create(authorId, baseSlug, article, at)
        .map(toArticle(_, favorited = false, favoritesCount = 0, following = false))
    yield article

  private def toArticleSummary(
      stored: StoredArticle,
      favorited: Boolean,
      favoritesCount: Int,
      following: Boolean
  ): ArticleSummary =
    ArticleSummary(
      slug = stored.slug,
      title = stored.title,
      description = stored.description,
      tagList = stored.tagList,
      createdAt = stored.createdAt,
      updatedAt = stored.updatedAt,
      favorited = favorited,
      favoritesCount = favoritesCount,
      author = Profile(
        username = stored.author.username,
        bio = stored.author.bio,
        image = stored.author.image,
        following = following
      )
    )

  private def toArticlesResponse(
      page: ArticlePage,
      favorited: StoredArticle => Boolean,
      favoritesCount: StoredArticle => Int,
      following: StoredArticle => Boolean
  ): MultipleArticlesResponse =
    val articles = page.articles.map { stored =>
      toArticleSummary(
        stored = stored,
        favorited = favorited(stored),
        favoritesCount = favoritesCount(stored),
        following = following(stored)
      )
    }
    MultipleArticlesResponse(articles = articles, articlesCount = page.articlesCount)

  override def list(
      viewerId: Option[UserId],
      filters: ArticleFilters,
      pagination: Pagination
  ): F[MultipleArticlesResponse] =
    articleRepository
      .list(filters, pagination)
      .map(page =>
        toArticlesResponse(
          page,
          _ => false,
          _ => 0,
          _ => false
        )
      )

  override def feed(userId: UserId, pagination: Pagination): F[MultipleArticlesResponse] =
    articleRepository
      .feed(userId, pagination)
      .map(page =>
        toArticlesResponse(
          page,
          _ => false,
          _ => 0,
          _ => true
        )
      )

object ArticleService:
  def live[F[_]: Async](articleRepository: ArticleRepository[F]): ArticleService[F] =
    LiveArticleService(articleRepository)

  def apply[F[_]: Async](articleRepository: ArticleRepository[F]): ArticleService[F] =
    live(articleRepository)

package com.example.realworld.service

import cats.effect.Async
import cats.syntax.all.*
import com.example.realworld.model.Article
import com.example.realworld.model.ArticleSummary
import com.example.realworld.model.MultipleArticlesResponse
import com.example.realworld.model.NewArticle
import com.example.realworld.model.Profile
import com.example.realworld.model.UpdateArticle
import com.example.realworld.model.UserId
import com.example.realworld.repository.ArticleFilters
import com.example.realworld.repository.ArticlePage
import com.example.realworld.repository.ArticleRepository
import com.example.realworld.repository.Pagination
import com.example.realworld.repository.StoredArticle

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

final class LiveArticleService[F[_]: Async](articleRepository: ArticleRepository[F])
    extends ArticleService[F]:

  private def toArticle(stored: StoredArticle): Article =
    Article(
      slug = stored.slug,
      title = stored.title,
      description = stored.description,
      body = stored.body,
      tagList = stored.tagList,
      createdAt = stored.createdAt,
      updatedAt = stored.updatedAt,
      favorited = stored.favorited,
      favoritesCount = stored.favoritesCount,
      author = Profile(
        username = stored.author.user.username,
        bio = stored.author.user.bio,
        image = stored.author.user.image,
        following = stored.author.following
      )
    )

  override def create(authorId: UserId, article: NewArticle): F[Article] =
    for
      at <- Async[F].realTimeInstant
      stored <- articleRepository.create(authorId, article, at)
    yield toArticle(stored)

  private def toArticleSummary(
      stored: StoredArticle
  ): ArticleSummary =
    ArticleSummary(
      slug = stored.slug,
      title = stored.title,
      description = stored.description,
      tagList = stored.tagList,
      createdAt = stored.createdAt,
      updatedAt = stored.updatedAt,
      favorited = stored.favorited,
      favoritesCount = stored.favoritesCount,
      author = Profile(
        username = stored.author.user.username,
        bio = stored.author.user.bio,
        image = stored.author.user.image,
        following = stored.author.following
      )
    )

  private def toArticlesResponse(page: ArticlePage): MultipleArticlesResponse =
    val articles = page.articles.map(toArticleSummary)
    MultipleArticlesResponse(articles = articles, articlesCount = page.articlesCount)

  override def list(
      viewerId: Option[UserId],
      filters: ArticleFilters,
      pagination: Pagination
  ): F[MultipleArticlesResponse] =
    articleRepository
      .list(viewerId, filters, pagination)
      .map(toArticlesResponse)

  override def feed(userId: UserId, pagination: Pagination): F[MultipleArticlesResponse] =
    articleRepository
      .feed(userId, pagination)
      .map(toArticlesResponse)

  override def find(viewerId: Option[UserId], slug: String): F[Option[Article]] =
    articleRepository
      .findBySlug(slug, viewerId)
      .map(_.map(toArticle))

  override def update(authorId: UserId, slug: String, update: UpdateArticle): F[Article] =
    for
      now <- Async[F].realTimeInstant
      updatedStored <- articleRepository.update(
        authorId = authorId,
        slug = slug,
        update = update,
        updatedAt = now
      )
    yield toArticle(updatedStored)

  override def favorite(userId: UserId, slug: String): F[Article] =
    for stored <- articleRepository.favorite(userId, slug)
    yield toArticle(stored)

  override def unfavorite(userId: UserId, slug: String): F[Article] =
    for stored <- articleRepository.unfavorite(userId, slug)
    yield toArticle(stored)

  override def delete(authorId: UserId, slug: String): F[Unit] =
    articleRepository.delete(authorId, slug)

  override def listTags: F[List[String]] =
    articleRepository.listTags

object ArticleService:
  def live[F[_]: Async](articleRepository: ArticleRepository[F]): ArticleService[F] =
    LiveArticleService(articleRepository)

  def apply[F[_]: Async](articleRepository: ArticleRepository[F]): ArticleService[F] =
    live(articleRepository)

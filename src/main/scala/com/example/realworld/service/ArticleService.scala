package com.example.realworld.service

import cats.effect.Async
import cats.syntax.all.*
import com.example.realworld.model.Article
import com.example.realworld.model.NewArticle
import com.example.realworld.model.Profile
import com.example.realworld.model.UserId
import com.example.realworld.repository.ArticleRepository
import com.example.realworld.repository.StoredArticle

import java.text.Normalizer
import java.util.Locale

trait ArticleService[F[_]]:
  def create(authorId: UserId, article: NewArticle): F[Article]

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

  private def toArticle(stored: StoredArticle): Article =
    Article(
      slug = stored.slug,
      title = stored.title,
      description = stored.description,
      body = stored.body,
      tagList = stored.tagList,
      createdAt = stored.createdAt,
      updatedAt = stored.updatedAt,
      favorited = false,
      favoritesCount = 0,
      author = Profile(
        username = stored.author.username,
        bio = stored.author.bio,
        image = stored.author.image,
        following = false
      )
    )

  override def create(authorId: UserId, article: NewArticle): F[Article] =
    val baseSlug = slugify(article.title)
    articleRepository.create(authorId, baseSlug, article).map(toArticle)

object ArticleService:
  def live[F[_]: Async](articleRepository: ArticleRepository[F]): ArticleService[F] =
    LiveArticleService(articleRepository)

  def apply[F[_]: Async](articleRepository: ArticleRepository[F]): ArticleService[F] =
    live(articleRepository)

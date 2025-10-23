package com.example.realworld.repository

import cats.ApplicativeThrow
import cats.effect.Async
import cats.syntax.all.*
import com.example.realworld.model.ArticleId
import com.example.realworld.model.NewArticle
import com.example.realworld.model.UserId
import doobie.ConnectionIO
import doobie.Read
import doobie.Transactor
import doobie.implicits.*
import doobie.implicits.legacy.instant.*
import doobie.util.update.Update

import java.time.Instant

trait ArticleRepository[F[_]]:
  def create(authorId: UserId, baseSlug: String, article: NewArticle): F[StoredArticle]

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

final class DoobieArticleRepository[F[_]: Async](xa: Transactor[F]) extends ArticleRepository[F]:
  final private case class ArticleRow(
      id: ArticleId,
      slug: String,
      title: String,
      description: String,
      body: String,
      createdAt: Instant,
      updatedAt: Instant,
      authorId: UserId
  ) derives Read

  private def insertArticle(
      authorId: UserId,
      slug: String,
      article: NewArticle
  ): ConnectionIO[ArticleRow] =
    sql"""
      INSERT INTO articles (slug, title, description, body, author_id)
      VALUES ($slug, ${article.title}, ${article.description}, ${article.body}, $authorId)
    """.update
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
      Update[(ArticleId, String)](
        "INSERT INTO article_tags (article_id, tag) VALUES (?, ?)"
      ).updateMany(tags.map(tag => (articleId, tag))).void

  private def selectAuthor(userId: UserId): ConnectionIO[StoredUser] =
    sql"""
      SELECT id, email, username, bio, image
      FROM users
      WHERE id = $userId
    """.query[StoredUser].unique

  private def loadSlugsWithPrefix(baseSlug: String): ConnectionIO[List[String]] =
    val prefixPattern = s"$baseSlug%"
    sql"""
      SELECT slug
      FROM articles
      WHERE slug LIKE $prefixPattern
    """.query[String].to[List]

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

  override def create(authorId: UserId, baseSlug: String, article: NewArticle): F[StoredArticle] =
    val sanitizedTags =
      article.tagList
        .getOrElse(Nil)
        .map(_.trim)
        .filter(_.nonEmpty)
        .distinct

    (for
      slug <- ensureUniqueSlug(baseSlug)
      row <- insertArticle(authorId, slug, article)
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

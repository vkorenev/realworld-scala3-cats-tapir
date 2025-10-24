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
import doobie.h2.implicits.*
import doobie.implicits.*
import doobie.util.fragments.whereAndOpt
import doobie.util.meta.Meta
import doobie.util.update.Update

import java.time.Instant

trait ArticleRepository[F[_]]:
  def create(authorId: UserId, baseSlug: String, article: NewArticle): F[StoredArticle]
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
  private given Meta[List[String]] =
    Meta.Advanced
      .array[String]("VARCHAR", "VARCHAR ARRAY", "ARRAY")
      .imap(_.toList)(_.toArray)

  private def filtersFragment(filters: ArticleFilters) =
    whereAndOpt(
      filters.author.map(author => fr"u.username = $author"),
      filters.tag.map(tag => fr"""
          EXISTS (
            SELECT 1
            FROM article_tags tag_filter
            WHERE tag_filter.article_id = a.id AND tag_filter.tag = $tag
          )
        """),
      filters.favorited.map(username => fr"""
          EXISTS (
            SELECT 1
            FROM article_favorites fav
            JOIN users fav_user ON fav.user_id = fav_user.id
            WHERE fav.article_id = a.id AND fav_user.username = $username
          )
        """)
    )

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

  private def selectArticlesQuery(
      filters: ArticleFilters,
      limit: Int,
      offset: Int
  ): ConnectionIO[List[(ArticleRow, StoredUser, Option[List[String]])]] =
    sql"""
      SELECT a.id,
             a.slug,
             a.title,
             a.description,
             a.body,
             a.created_at,
             a.updated_at,
             a.author_id,
             u.id,
             u.email,
             u.username,
             u.bio,
             u.image,
             (
               SELECT ARRAY_AGG(DISTINCT tag)
               FROM article_tags tags
               WHERE tags.article_id = a.id
             ) AS tags
      FROM articles a
      JOIN users u ON a.author_id = u.id
      ${filtersFragment(filters)}
      ORDER BY a.created_at DESC, a.id DESC
      LIMIT $limit OFFSET $offset
    """.query[(ArticleRow, StoredUser, Option[List[String]])].to[List]

  private def selectArticlesCount(filters: ArticleFilters): ConnectionIO[Int] =
    sql"""
      SELECT COUNT(*)
      FROM (
        SELECT DISTINCT a.id
        FROM articles a
        JOIN users u ON a.author_id = u.id
        ${filtersFragment(filters)}
      ) counted
    """.query[Int].unique

  private def toStoredArticles(
      rows: List[(ArticleRow, StoredUser, Option[List[String]])]
  ): List[StoredArticle] =
    rows.map { case (row, author, maybeTags) =>
      StoredArticle(
        id = row.id,
        slug = row.slug,
        title = row.title,
        description = row.description,
        body = row.body,
        tagList = maybeTags.map(_.distinct).getOrElse(Nil),
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
  ): ConnectionIO[List[(ArticleRow, StoredUser, Option[List[String]])]] =
    sql"""
      SELECT a.id,
             a.slug,
             a.title,
             a.description,
             a.body,
             a.created_at,
             a.updated_at,
             a.author_id,
             u.id,
             u.email,
             u.username,
             u.bio,
             u.image,
             (
               SELECT ARRAY_AGG(DISTINCT tag)
               FROM article_tags tags
               WHERE tags.article_id = a.id
             ) AS tags
      FROM articles a
      JOIN user_follows f ON f.followed_id = a.author_id
      JOIN users u ON a.author_id = u.id
      WHERE f.follower_id = $userId
      ORDER BY a.created_at DESC, a.id DESC
      LIMIT $limit OFFSET $offset
    """.query[(ArticleRow, StoredUser, Option[List[String]])].to[List]

  private def selectFeedCount(userId: UserId): ConnectionIO[Int] =
    sql"""
      SELECT COUNT(*)
      FROM (
        SELECT DISTINCT a.id
        FROM articles a
        JOIN user_follows f ON f.followed_id = a.author_id
        WHERE f.follower_id = $userId
      ) counted
    """.query[Int].unique

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

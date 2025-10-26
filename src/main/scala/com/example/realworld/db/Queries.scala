package com.example.realworld.db

import com.example.realworld.model.ArticleId
import com.example.realworld.model.NewArticle
import com.example.realworld.model.UserId
import com.example.realworld.repository.ArticleFilters
import com.example.realworld.repository.StoredUser
import doobie.Query0
import doobie.Read
import doobie.Update
import doobie.Update0
import doobie.h2.implicits.*
import doobie.implicits.*
import doobie.util.fragment.Fragment
import doobie.util.fragments.whereAndOpt
import doobie.util.meta.Meta

import java.time.Instant

object Queries:

  private def filtersFragment(filters: ArticleFilters): Fragment =
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

  final case class ArticleRow(
      id: ArticleId,
      slug: String,
      title: String,
      description: String,
      body: String,
      createdAt: Instant,
      updatedAt: Instant,
      authorId: UserId
  ) derives Read

  def insertUser(username: String, email: String, passwordHash: String): Update0 =
    sql"""
      INSERT INTO users (username, email, password, bio, image)
      VALUES ($username, $email, $passwordHash, NULL, NULL)
    """.update

  def selectByEmailWithPassword(email: String): Query0[(StoredUser, String)] =
    sql"""
      SELECT id, email, username, bio, image, password
      FROM users
      WHERE email = $email
    """.query[(StoredUser, String)]

  def selectByUsername(username: String): Query0[StoredUser] =
    sql"""
      SELECT id, email, username, bio, image
      FROM users
      WHERE username = $username
    """.query[StoredUser]

  def selectById(id: UserId): Query0[StoredUser] =
    sql"""
      SELECT id, email, username, bio, image
      FROM users
      WHERE id = $id
    """.query[StoredUser]

  def selectByIdForUpdate(id: UserId): Query0[(StoredUser, String)] =
    sql"""
      SELECT id, email, username, bio, image, password
      FROM users
      WHERE id = $id
      FOR UPDATE
    """.query[(StoredUser, String)]

  def updateUser(
      id: UserId,
      email: String,
      username: String,
      bio: Option[String],
      image: Option[String],
      passwordHash: String
  ): Update0 =
    sql"""
      UPDATE users
      SET email = $email,
          username = $username,
          bio = $bio,
          image = $image,
          password = $passwordHash
      WHERE id = $id
    """.update

  def selectFollowing(followerId: UserId, followedId: UserId): Query0[Boolean] =
    sql"""
      SELECT EXISTS (
        SELECT 1
        FROM user_follows
        WHERE follower_id = $followerId AND followed_id = $followedId
      )
    """.query[Boolean]

  def insertFollow(followerId: UserId, followedId: UserId): Update0 =
    sql"""
      INSERT INTO user_follows (follower_id, followed_id)
      VALUES ($followerId, $followedId)
      ON CONFLICT DO NOTHING
    """.update

  def deleteFollow(followerId: UserId, followedId: UserId): Update0 =
    sql"""
      DELETE FROM user_follows
      WHERE follower_id = $followerId AND followed_id = $followedId
    """.update

  def insertArticle(slug: String, article: NewArticle, authorId: UserId, at: Instant): Update0 =
    sql"""
      INSERT INTO articles (slug, title, description, body, created_at, updated_at, author_id)
      VALUES ($slug, ${article.title}, ${article.description}, ${article.body}, $at, $at, $authorId)
    """.update

  val insertArticleTag: Update[(ArticleId, String)] =
    Update[(ArticleId, String)](
      "INSERT INTO article_tags (article_id, tag) VALUES (?, ?)"
    )

  def selectSlugsWithPrefix(pattern: String): Query0[String] =
    sql"""
      SELECT slug
      FROM articles
      WHERE slug LIKE $pattern
    """.query[String]

  def selectArticles(
      filters: ArticleFilters,
      limit: Int,
      offset: Int
  ): Query0[(ArticleRow, StoredUser, List[String])] =
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
    """.query[(ArticleRow, StoredUser, List[String])]

  def selectArticlesCount(filters: ArticleFilters): Query0[Long] =
    sql"""
      SELECT COUNT(*)
      FROM (
        SELECT DISTINCT a.id
        FROM articles a
        JOIN users u ON a.author_id = u.id
      ${filtersFragment(filters)}
      ) counted
    """.query[Long]

  def selectFeedArticles(
      userId: UserId,
      limit: Int,
      offset: Int
  ): Query0[(ArticleRow, StoredUser, List[String])] =
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
    """.query[(ArticleRow, StoredUser, List[String])]

  def selectFeedCount(userId: UserId): Query0[Long] =
    sql"""
      SELECT COUNT(*)
      FROM (
        SELECT DISTINCT a.id
        FROM articles a
        JOIN user_follows f ON f.followed_id = a.author_id
        WHERE f.follower_id = $userId
      ) counted
    """.query[Long]

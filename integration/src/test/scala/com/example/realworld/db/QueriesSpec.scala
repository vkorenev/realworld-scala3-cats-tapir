package com.example.realworld.db

import cats.effect.IO
import cats.effect.Resource
import com.example.realworld.model.ArticleId
import com.example.realworld.model.NewArticle
import com.example.realworld.model.UserId
import com.example.realworld.repository.ArticleFilters
import doobie.Transactor
import doobie.munit.IOChecker
import munit.CatsEffectSuite

import java.time.Instant
import java.util.UUID

class QueriesSpec extends CatsEffectSuite with IOChecker:
  import Queries.*

  private val transactorFixture = ResourceSuiteLocalFixture(
    "queries-transactor",
    for
      dbName <- Resource.eval(IO(s"queries-${UUID.randomUUID().toString.replace("-", "")}"))
      container <- PostgresTestContainer.resource[IO](dbName)
      xa <- Database.transactor[IO](TestDatabaseConfig.fromContainer(container))
      _ <- Resource.eval(Database.initialize[IO](xa))
    yield xa
  )

  override def munitFixtures = List(transactorFixture)

  override lazy val transactor: Transactor[IO] = transactorFixture()

  private val sampleArticle =
    NewArticle(
      title = "",
      description = "",
      body = "",
      tagList = None
    )

  private val emptyFilters = ArticleFilters(tag = None, author = None, favorited = None)

  test("insertUser"):
    check(insertUser("", "", ""))

  test("selectUserByEmailWithPassword"):
    check(selectUserByEmailWithPassword(""))

  test("selectUserByUsername"):
    check(selectUserByUsername(""))

  test("selectUserById"):
    check(selectUserById(UserId(0)))

  test("selectUserByIdForUpdate"):
    check(selectUserByIdForUpdate(UserId(0)))

  test("updateUser"):
    check(
      updateUser(
        id = UserId(0),
        email = "",
        username = "",
        bio = Some(""),
        image = Some(""),
        passwordHash = ""
      )
    )

  test("selectFollowing"):
    check(selectFollowing(UserId(0), UserId(0)))

  test("insertFollow"):
    check(insertFollow(UserId(0), UserId(0)))

  test("deleteFollow"):
    check(deleteFollow(UserId(0), UserId(0)))

  test("insertArticle"):
    check(insertArticle("", sampleArticle, UserId(0), Instant.EPOCH))

  test("updateArticle"):
    check(updateArticle(ArticleId(0), "", "", "", "", Instant.EPOCH))

  test("insertArticleTag"):
    check(insertArticleTag)

  test("deleteArticle"):
    check(deleteArticle(ArticleId(0)))

  test("insertArticleFavorite"):
    check(insertArticleFavorite(ArticleId(0), UserId(0)))

  test("deleteArticleFavorite"):
    check(deleteArticleFavorite(ArticleId(0), UserId(0)))

  test("selectArticleSlugsLike"):
    check(selectArticleSlugsLike(""))

  test("selectArticles with empty filters"):
    check(selectArticles(emptyFilters, viewerId = None, limit = 0, offset = 0))

  test("selectArticlesCount with empty filters"):
    check(selectArticlesCount(emptyFilters))

  test("selectFeedArticles"):
    check(selectFeedArticles(UserId(0), limit = 0, offset = 0))

  test("selectFeedCount"):
    check(selectFeedCount(UserId(0)))

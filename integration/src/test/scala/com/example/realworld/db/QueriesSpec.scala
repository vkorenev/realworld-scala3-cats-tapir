package com.example.realworld.db

import cats.effect.IO
import cats.effect.Resource
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

  test("insertUser compiles"):
    check(insertUser("", "", ""))

  test("selectByEmailWithPassword compiles"):
    check(selectByEmailWithPassword(""))

  test("selectByUsername compiles"):
    check(selectByUsername(""))

  test("selectById compiles"):
    check(selectById(UserId(0)))

  test("selectByIdForUpdate compiles"):
    check(selectByIdForUpdate(UserId(0)))

  test("updateUser compiles"):
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

  test("selectFollowing compiles"):
    check(selectFollowing(UserId(0), UserId(0)))

  test("insertFollow compiles"):
    check(insertFollow(UserId(0), UserId(0)))

  test("deleteFollow compiles"):
    check(deleteFollow(UserId(0), UserId(0)))

  test("insertArticle compiles"):
    check(insertArticle("", sampleArticle, UserId(0), Instant.EPOCH))

  test("insertArticleTag compiles"):
    check(insertArticleTag)

  test("selectSlugsWithPrefix compiles"):
    check(selectSlugsWithPrefix(""))

  test("selectArticles compiles with empty filters"):
    check(selectArticles(emptyFilters, limit = 0, offset = 0))

  test("selectArticlesCount compiles with empty filters"):
    check(selectArticlesCount(emptyFilters))

  test("selectFeedArticles compiles"):
    check(selectFeedArticles(UserId(0), limit = 0, offset = 0))

  test("selectFeedCount compiles"):
    check(selectFeedCount(UserId(0)))

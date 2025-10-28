package com.example.realworld.model

import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec
import sttp.tapir.Schema

import java.time.Instant

final case class NewArticle(
    title: String,
    description: String,
    body: String,
    tagList: Option[List[String]]
) derives ConfiguredJsonValueCodec,
      Schema

final case class NewArticleRequest(article: NewArticle) derives ConfiguredJsonValueCodec, Schema

final case class Article(
    slug: String,
    title: String,
    description: String,
    body: String,
    tagList: List[String],
    createdAt: Instant,
    updatedAt: Instant,
    favorited: Boolean,
    favoritesCount: Int,
    author: Profile
) derives ConfiguredJsonValueCodec,
      Schema

final case class ArticleResponse(article: Article) derives ConfiguredJsonValueCodec, Schema

final case class ArticleSummary(
    slug: String,
    title: String,
    description: String,
    tagList: List[String],
    createdAt: Instant,
    updatedAt: Instant,
    favorited: Boolean,
    favoritesCount: Int,
    author: Profile
) derives ConfiguredJsonValueCodec,
      Schema

final case class MultipleArticlesResponse(
    articles: List[ArticleSummary],
    articlesCount: Int
) derives ConfiguredJsonValueCodec,
      Schema

final case class UpdateArticle(
    title: Option[String],
    description: Option[String],
    body: Option[String]
) derives ConfiguredJsonValueCodec,
      Schema

final case class UpdateArticleRequest(article: UpdateArticle)
    derives ConfiguredJsonValueCodec,
      Schema

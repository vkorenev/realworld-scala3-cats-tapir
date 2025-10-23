package com.example.realworld.model

import doobie.util.meta.Meta

opaque type ArticleId = Long

object ArticleId:
  inline def apply(value: Long): ArticleId = value
  inline def value(id: ArticleId): Long = id
  given Meta[ArticleId] = Meta[Long].imap(apply)(value)

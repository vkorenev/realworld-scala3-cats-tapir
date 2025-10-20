package com.example.realworld.model

import doobie.util.meta.Meta

opaque type UserId = Long

object UserId:
  inline def apply(value: Long): UserId = value
  inline def value(id: UserId): Long = id
  given Meta[UserId] = Meta[Long].imap(apply)(value)

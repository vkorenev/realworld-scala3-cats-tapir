package com.example.realworld.db

import cats.effect.Async
import doobie.implicits.*
import doobie.util.transactor.Transactor

object Database:
  def initialize[F[_]: Async](xa: Transactor[F]): F[Unit] =
    (for
      _ <-
        sql"""
          CREATE TABLE IF NOT EXISTS users (
            id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            username VARCHAR(255) NOT NULL UNIQUE,
            email VARCHAR(255) NOT NULL UNIQUE,
            password VARCHAR(255) NOT NULL,
            bio VARCHAR(1024),
            image VARCHAR(1024)
          )
        """.update.run
      _ <-
        sql"""
          CREATE TABLE IF NOT EXISTS user_follows (
            follower_id BIGINT NOT NULL,
            followed_id BIGINT NOT NULL,
            PRIMARY KEY (follower_id, followed_id),
            FOREIGN KEY (follower_id) REFERENCES users(id) ON DELETE CASCADE,
            FOREIGN KEY (followed_id) REFERENCES users(id) ON DELETE CASCADE
          )
        """.update.run
      _ <-
        sql"""
          CREATE TABLE IF NOT EXISTS articles (
            id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            slug VARCHAR(255) NOT NULL UNIQUE,
            title VARCHAR(255) NOT NULL,
            description VARCHAR(1024) NOT NULL,
            body TEXT NOT NULL,
            created_at TIMESTAMP WITH TIME ZONE NOT NULL ,
            updated_at TIMESTAMP WITH TIME ZONE NOT NULL ,
            author_id BIGINT NOT NULL,
            FOREIGN KEY (author_id) REFERENCES users(id) ON DELETE CASCADE
          )
        """.update.run
      _ <-
        sql"""
          CREATE TABLE IF NOT EXISTS article_tags (
            article_id BIGINT NOT NULL,
            tag VARCHAR(255) NOT NULL,
            PRIMARY KEY (article_id, tag),
            FOREIGN KEY (article_id) REFERENCES articles(id) ON DELETE CASCADE
          )
        """.update.run
      _ <-
        sql"""
          CREATE TABLE IF NOT EXISTS article_favorites (
            article_id BIGINT NOT NULL,
            user_id BIGINT NOT NULL,
            PRIMARY KEY (article_id, user_id),
            FOREIGN KEY (article_id) REFERENCES articles(id) ON DELETE CASCADE,
            FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
          )
        """.update.run
      _ <-
        sql"""
          CREATE TABLE IF NOT EXISTS article_comments (
            id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            article_id BIGINT NOT NULL,
            author_id BIGINT NOT NULL,
            body TEXT NOT NULL,
            created_at TIMESTAMP WITH TIME ZONE NOT NULL,
            updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
            FOREIGN KEY (article_id) REFERENCES articles(id) ON DELETE CASCADE,
            FOREIGN KEY (author_id) REFERENCES users(id) ON DELETE CASCADE
          )
        """.update.run
    yield ()).transact(xa)

package com.example.realworld.model

import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec
import sttp.tapir.Schema

final case class NewUser(username: String, email: String, password: String)
    derives ConfiguredJsonValueCodec,
      Schema

final case class LoginUser(email: String, password: String) derives ConfiguredJsonValueCodec, Schema

final case class NewUserRequest(user: NewUser) derives ConfiguredJsonValueCodec, Schema

final case class LoginUserRequest(user: LoginUser) derives ConfiguredJsonValueCodec, Schema

final case class UpdateUser(
    email: Option[String],
    username: Option[String],
    password: Option[String],
    image: Option[String],
    bio: Option[String]
) derives ConfiguredJsonValueCodec,
      Schema

final case class UpdateUserRequest(user: UpdateUser) derives ConfiguredJsonValueCodec, Schema

final case class User(
    email: String,
    token: String,
    username: String,
    bio: Option[String],
    image: Option[String]
) derives ConfiguredJsonValueCodec,
      Schema

final case class UserResponse(user: User) derives ConfiguredJsonValueCodec, Schema

final case class Profile(
    username: String,
    bio: Option[String],
    image: Option[String],
    following: Boolean
) derives ConfiguredJsonValueCodec,
      Schema

final case class ProfileResponse(profile: Profile) derives ConfiguredJsonValueCodec, Schema

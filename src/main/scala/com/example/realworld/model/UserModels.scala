package com.example.realworld.model

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import sttp.tapir.Schema

final case class NewUser(username: String, email: String, password: String)

object NewUser:
  given JsonValueCodec[NewUser] = JsonCodecMaker.make
  given Schema[NewUser] = Schema.derived

final case class LoginUser(email: String, password: String)

object LoginUser:
  given JsonValueCodec[LoginUser] = JsonCodecMaker.make
  given Schema[LoginUser] = Schema.derived

final case class NewUserRequest(user: NewUser)

object NewUserRequest:
  given JsonValueCodec[NewUserRequest] = JsonCodecMaker.make
  given Schema[NewUserRequest] = Schema.derived

final case class LoginUserRequest(user: LoginUser)

object LoginUserRequest:
  given JsonValueCodec[LoginUserRequest] = JsonCodecMaker.make
  given Schema[LoginUserRequest] = Schema.derived

final case class UpdateUser(
    email: Option[String],
    username: Option[String],
    password: Option[String],
    image: Option[String],
    bio: Option[String]
)

object UpdateUser:
  given JsonValueCodec[UpdateUser] = JsonCodecMaker.make
  given Schema[UpdateUser] = Schema.derived

final case class UpdateUserRequest(user: UpdateUser)

object UpdateUserRequest:
  given JsonValueCodec[UpdateUserRequest] = JsonCodecMaker.make
  given Schema[UpdateUserRequest] = Schema.derived

final case class User(
    email: String,
    token: String,
    username: String,
    bio: Option[String],
    image: Option[String]
)

object User:
  given JsonValueCodec[User] = JsonCodecMaker.make
  given Schema[User] = Schema.derived

final case class UserResponse(user: User)

object UserResponse:
  given JsonValueCodec[UserResponse] = JsonCodecMaker.make
  given Schema[UserResponse] = Schema.derived

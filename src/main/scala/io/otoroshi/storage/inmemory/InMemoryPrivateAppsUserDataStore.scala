package io.otoroshi.storage.inmemory

import io.otoroshi.env.Env
import io.otoroshi.models.{Key, PrivateAppsUser, PrivateAppsUserDataStore}
import play.api.libs.json.{Format, Json}
import play.api.libs.json.JodaWrites._
import play.api.libs.json.JodaReads._
import io.otoroshi.storage.{RedisLike, RedisLikeStore}

class InMemoryPrivateAppsUserDataStore(redisCli: RedisLike)
    extends PrivateAppsUserDataStore
    with RedisLikeStore[PrivateAppsUser] {
  private val _fmt                                       = Json.format[PrivateAppsUser]
  override def redisLike(implicit env: Env): RedisLike   = redisCli
  override def fmt: Format[PrivateAppsUser]              = _fmt
  override def key(id: String): Key                      = Key.Empty / "opun" / "users" / "private" / id
  override def extractId(value: PrivateAppsUser): String = value.randomId
}

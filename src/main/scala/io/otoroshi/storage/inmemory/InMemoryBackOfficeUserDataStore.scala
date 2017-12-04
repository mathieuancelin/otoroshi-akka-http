package io.otoroshi.storage.inmemory

import akka.http.scaladsl.util.FastFuture
import akka.util.ByteString
import io.otoroshi.env.Env
import io.otoroshi.models.{BackOfficeUser, BackOfficeUserDataStore, Key}
import play.api.libs.json.{Format, JsValue, Json}
import play.api.libs.json.JodaWrites._
import play.api.libs.json.JodaReads._
import io.otoroshi.storage.{RedisLike, RedisLikeStore}

import scala.concurrent.{ExecutionContext, Future}

class InMemoryBackOfficeUserDataStore(redisCli: RedisLike)
    extends BackOfficeUserDataStore
    with RedisLikeStore[BackOfficeUser] {

  private val _fmt                                      = Json.format[BackOfficeUser]
  override def redisLike(implicit env: Env): RedisLike  = redisCli
  override def fmt: Format[BackOfficeUser]              = _fmt
  override def key(id: String): Key                     = Key.Empty / "opun" / "users" / "backoffice" / id
  override def extractId(value: BackOfficeUser): String = value.randomId

  override def blacklisted(email: String)(implicit ec: ExecutionContext, env: Env): Future[Boolean] =
    redisCli.sismember(s"opun:users:blacklist:backoffice", email)

  override def hasAlreadyLoggedIn(email: String)(implicit ec: ExecutionContext, env: Env): Future[Boolean] =
    redisCli.sismember(s"opun:users:alreadyloggedin", email)

  override def alreadyLoggedIn(email: String)(implicit ec: ExecutionContext, env: Env): Future[Long] =
    redisCli.sadd(s"opun:users:alreadyloggedin", email)

  override def sessions()(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] =
    redisCli
      .keys("opun:users:backoffice:*")
      .flatMap(keys => if (keys.isEmpty) FastFuture.successful(Seq.empty[Option[ByteString]]) else redisCli.mget(keys: _*))
      .map { seq =>
        seq
          .filter(_.isDefined)
          .map(_.get)
          .map(v => Json.parse(v.utf8String))
      }

  override def discardSession(id: String)(implicit ec: ExecutionContext, env: Env): Future[Long] =
    redisCli.del(s"opun:users:backoffice:$id")

  def discardAllSessions()(implicit ec: ExecutionContext, env: Env): Future[Long] =
    redisCli.keys("opun:users:backoffice:*").flatMap { keys =>
      redisCli.del(keys: _*)
    }
}

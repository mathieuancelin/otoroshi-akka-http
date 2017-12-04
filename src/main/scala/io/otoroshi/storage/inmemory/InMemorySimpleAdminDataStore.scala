package io.otoroshi.storage.inmemory

import akka.http.scaladsl.util.FastFuture
import akka.util.ByteString
import io.otoroshi.env.Env
import io.otoroshi.models.SimpleAdminDataStore
import org.joda.time.DateTime
import play.api.libs.json.JodaWrites._
import play.api.Logger
import play.api.libs.json._
import io.otoroshi.storage.RedisLike

import scala.concurrent.{ExecutionContext, Future}

class InMemorySimpleAdminDataStore(redisCli: RedisLike) extends SimpleAdminDataStore {

  lazy val logger = Logger("otoroshi-in-memory-simple-admin-datastore")

  def key(id: String): String = s"opun:admins:$id"

  override def findByUsername(username: String)(implicit ec: ExecutionContext, env: Env): Future[Option[JsValue]] =
    redisCli.get(key(username)).map(_.map(v => Json.parse(v.utf8String)))

  override def findAll()(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] =
    redisCli
      .keys(key("*"))
      .flatMap(keys => if (keys.isEmpty) FastFuture.successful(Seq.empty[Option[ByteString]]) else redisCli.mget(keys: _*))
      .map(seq => seq.filter(_.isDefined).map(_.get).map(v => Json.parse(v.utf8String)))

  override def deleteUser(username: String)(implicit ec: ExecutionContext, env: Env): Future[Long] =
    redisCli.del(key(username))

  override def registerUser(username: String, password: String, label: String)(implicit ec: ExecutionContext,
                                                                               env: Env): Future[Boolean] = {
    // logger.warn(password)
    redisCli.set(key(username),
                 Json.stringify(
                   Json.obj(
                     "username"  -> username,
                     "password"  -> password,
                     "label"     -> label,
                     "createdAt" -> DateTime.now()
                   )
                 ))
  }

  override def hasAlreadyLoggedIn(username: String)(implicit ec: ExecutionContext, env: Env): Future[Boolean] =
    redisCli.sismember(s"opun:users:alreadyloggedin", username)

  override def alreadyLoggedIn(email: String)(implicit ec: ExecutionContext, env: Env): Future[Long] =
    redisCli.sadd(s"opun:users:alreadyloggedin", email)
}

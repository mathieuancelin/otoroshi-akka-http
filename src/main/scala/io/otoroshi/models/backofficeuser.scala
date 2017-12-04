package io.otoroshi.models

import io.otoroshi.env.Env
import org.joda.time.DateTime
import play.api.libs.json.JsValue
import io.otoroshi.storage.BasicStore
import play.api.libs.json.JodaWrites._

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}

case class BackOfficeUser(randomId: String,
                          name: String,
                          email: String,
                          profile: JsValue,
                          createdAt: DateTime = DateTime.now(),
                          expiredAt: DateTime = DateTime.now()) {

  def save(duration: Duration)(implicit ec: ExecutionContext, env: Env): Future[BackOfficeUser] = {
    val withDuration = this.copy(expiredAt = expiredAt.plusMillis(duration.toMillis.toInt))
    env.datastores.backOfficeUserDataStore.set(withDuration, Some(duration)).map(_ => withDuration)
  }

  def delete()(implicit ec: ExecutionContext, env: Env): Future[Boolean] =
    env.datastores.backOfficeUserDataStore.delete(randomId)
}

trait BackOfficeUserDataStore extends BasicStore[BackOfficeUser] {
  def blacklisted(email: String)(implicit ec: ExecutionContext, env: Env): Future[Boolean]
  def hasAlreadyLoggedIn(email: String)(implicit ec: ExecutionContext, env: Env): Future[Boolean]
  def alreadyLoggedIn(email: String)(implicit ec: ExecutionContext, env: Env): Future[Long]
  def sessions()(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]]
  def discardSession(id: String)(implicit ec: ExecutionContext, env: Env): Future[Long]
  def discardAllSessions()(implicit ec: ExecutionContext, env: Env): Future[Long]
}

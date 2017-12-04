package io.otoroshi.models

import io.otoroshi.env.Env
import play.api.libs.json._
import io.otoroshi.storage.BasicStore
import org.joda.time.DateTime
import play.api.libs.json.JodaWrites._
import play.api.libs.json.JodaReads._

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}

case class PrivateAppsUser(randomId: String,
                           name: String,
                           email: String,
                           profile: JsValue,
                           createdAt: DateTime = DateTime.now(),
                           expiredAt: DateTime = DateTime.now()) {
  def opunData: Option[Map[String, String]] = (profile \ "app_metadata" \ "opun_data").asOpt[Map[String, String]]
  def picture: Option[String]               = (profile \ "picture").asOpt[String]
  def field(name: String): Option[String]   = (profile \ "name").asOpt[String]
  def userId: Option[String]                = (profile \ "user_id").asOpt[String]

  def save(duration: Duration)(implicit ec: ExecutionContext, env: Env): Future[PrivateAppsUser] =
    env.datastores.privateAppsUserDataStore
      .set(this.copy(expiredAt = DateTime.now().plusMillis(duration.toMillis.toInt)), Some(duration))
      .map(_ => this)

  def delete()(implicit ec: ExecutionContext, env: Env): Future[Boolean] =
    env.datastores.privateAppsUserDataStore.delete(randomId)

  def toJson: JsValue = PrivateAppsUser.fmt.writes(this)
}

object PrivateAppsUser {
  val fmt = Json.format[PrivateAppsUser]
}

trait PrivateAppsUserDataStore extends BasicStore[PrivateAppsUser]

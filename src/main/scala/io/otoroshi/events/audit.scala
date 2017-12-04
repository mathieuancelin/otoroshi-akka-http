package io.otoroshi.events

import akka.util.ByteString
import io.otoroshi.env.Env
import io.otoroshi.models.{ApiKey, BackOfficeUser, ServiceDescriptor, Target}
import org.joda.time.DateTime
import play.api.libs.json.JodaWrites._
import play.api.libs.json._

import scala.concurrent.{ExecutionContext, Future}

trait AuditEvent extends AnalyticEvent {
  override def `@type`: String = "AuditEvent"
}

case class BackOfficeEvent(`@id`: String,
                           `@env`: String,
                           user: BackOfficeUser,
                           action: String,
                           message: String,
                           from: String,
                           metadata: JsObject = Json.obj(),
                           `@timestamp`: DateTime = DateTime.now())
    extends AuditEvent {

  override def `@service`: String   = "Otoroshi"
  override def `@product`: String   = "opun"
  override def `@serviceId`: String = "--"

  override def toJson: JsValue = Json.obj(
    "@id"          -> `@id`,
    "@timestamp"   -> Writes.DefaultJodaDateWrites.writes(`@timestamp`),
    "@type"        -> `@type`,
    "@product"     -> `@product`,
    "@serviceId"   -> `@serviceId`,
    "@service"     -> `@service`,
    "@env"         -> `@env`,
    "audit"        -> "BackOfficeEvent",
    "userName"     -> user.name,
    "userEmail"    -> user.email,
    "user"         -> user.profile,
    "userRandomId" -> user.randomId,
    "action"       -> action,
    "from"         -> from,
    "message"      -> message,
    "metadata"     -> metadata
  )
}

case class AdminApiEvent(`@id`: String,
                         `@env`: String,
                         apiKey: Option[ApiKey],
                         user: Option[JsValue],
                         action: String,
                         message: String,
                         from: String,
                         metadata: JsValue = Json.obj(),
                         `@timestamp`: DateTime = DateTime.now())
    extends AuditEvent {

  override def `@service`: String   = "Otoroshi"
  override def `@product`: String   = "opun"
  override def `@serviceId`: String = "--"

  override def toJson: JsValue = Json.obj(
    "@id"        -> `@id`,
    "@timestamp" -> Writes.DefaultJodaDateWrites.writes(`@timestamp`),
    "@type"      -> `@type`,
    "@product"   -> `@product`,
    "@serviceId" -> `@serviceId`,
    "@service"   -> `@service`,
    "@env"       -> `@env`,
    "audit"      -> "AdminApiEvent",
    "user"       -> user.getOrElse(JsNull).as[JsValue],
    "apiKey"     -> apiKey.map(ak => ak.toJson).getOrElse(JsNull).as[JsValue],
    "action"     -> action,
    "from"       -> from,
    "message"    -> message,
    "metadata"   -> metadata
  )
}

case class CircuitBreakerOpenedEvent(`@id`: String,
                                     `@env`: String,
                                     target: Target,
                                     service: ServiceDescriptor,
                                     `@timestamp`: DateTime = DateTime.now())
    extends AuditEvent {

  override def `@service`: String   = "Otoroshi"
  override def `@product`: String   = "opun"
  override def `@serviceId`: String = service.id

  override def toJson: JsValue = Json.obj(
    "@id"        -> `@id`,
    "@timestamp" -> Writes.DefaultJodaDateWrites.writes(`@timestamp`),
    "@type"      -> `@type`,
    "@product"   -> `@product`,
    "@serviceId" -> `@serviceId`,
    "@service"   -> `@service`,
    "@env"       -> `@env`,
    "audit"      -> "CircuitBreakerOpenedEvent",
    "target"     -> target.toJson,
    "service"    -> service.toJson
  )
}

case class CircuitBreakerClosedEvent(`@id`: String,
                                     `@env`: String,
                                     target: Target,
                                     service: ServiceDescriptor,
                                     `@timestamp`: DateTime = DateTime.now())
    extends AuditEvent {

  override def `@service`: String   = "Otoroshi"
  override def `@product`: String   = "opun"
  override def `@serviceId`: String = service.id

  override def toJson: JsValue = Json.obj(
    "@id"        -> `@id`,
    "@timestamp" -> Writes.DefaultJodaDateWrites.writes(`@timestamp`),
    "@type"      -> `@type`,
    "@product"   -> `@product`,
    "@serviceId" -> `@serviceId`,
    "@service"   -> `@service`,
    "@env"       -> `@env`,
    "audit"      -> "CircuitBreakerClosedEvent",
    "target"     -> target.toJson,
    "service"    -> service.toJson
  )
}

case class MaxConcurrentRequestReachedEvent(`@id`: String,
                                            `@env`: String,
                                            limit: Long,
                                            current: Long,
                                            `@timestamp`: DateTime = DateTime.now())
    extends AuditEvent {

  override def `@service`: String   = "Otoroshi"
  override def `@product`: String   = "opun"
  override def `@serviceId`: String = "--"

  override def toJson: JsValue = Json.obj(
    "@id"        -> `@id`,
    "@timestamp" -> Writes.DefaultJodaDateWrites.writes(`@timestamp`),
    "@type"      -> `@type`,
    "@product"   -> `@product`,
    "@serviceId" -> `@serviceId`,
    "@service"   -> `@service`,
    "@env"       -> `@env`,
    "audit"      -> "MaxConcurrentRequestReachedEvent",
    "limit"      -> limit,
    "current"    -> current
  )
}

object Audit {
  def send[A <: AuditEvent](audit: A)(implicit env: Env): Unit = {
    implicit val ec = env.executionContext
    audit.toAnalytics()
    env.datastores.auditDataStore.push(audit)
  }
}

trait AuditDataStore {
  def count()(implicit ec: ExecutionContext, env: Env): Future[Long]
  def findAllRaw(from: Long = 0, to: Long = 1000)(implicit ec: ExecutionContext, env: Env): Future[Seq[ByteString]]
  def push(event: AuditEvent)(implicit ec: ExecutionContext, env: Env): Future[Long]
}

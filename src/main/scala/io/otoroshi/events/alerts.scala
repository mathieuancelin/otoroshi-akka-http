package io.otoroshi.events

import java.util.concurrent.{Executors, TimeUnit}

import akka.actor.{Actor, PoisonPill, Props, Terminated}
import akka.http.javadsl.model.headers.HttpCredentials
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{OverflowStrategy, QueueOfferResult}
import akka.util.ByteString
import io.otoroshi.env.Env
import io.otoroshi.models.{ApiKey, BackOfficeUser, ServiceDescriptor, Target}
import org.joda.time.DateTime
import play.api.libs.json.JodaWrites._
import play.api.Logger
import play.api.libs.json.{JsValue, Json, Writes}

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

trait AlertEvent extends AnalyticEvent {
  override def `@type`: String = "AlertEvent"
}

case class MaxConcurrentRequestReachedAlert(`@id`: String,
                                            `@env`: String,
                                            limit: Long,
                                            current: Long,
                                            `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {

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
    "alert"      -> "MaxConcurrentRequestReachedAlert",
    "limit"      -> limit,
    "current"    -> current
  )
}

case class CircuitBreakerOpenedAlert(`@id`: String,
                                     `@env`: String,
                                     target: Target,
                                     service: ServiceDescriptor,
                                     `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {

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
    "alert"      -> "CircuitBreakerOpenedAlert",
    "target"     -> target.toJson,
    "service"    -> service.toJson
  )
}

case class CircuitBreakerClosedAlert(`@id`: String,
                                     `@env`: String,
                                     target: Target,
                                     service: ServiceDescriptor,
                                     `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {

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
    "alert"      -> "CircuitBreakerClosedAlert",
    "target"     -> target.toJson,
    "service"    -> service.toJson
  )
}

case class SessionDiscardedAlert(`@id`: String,
                                 `@env`: String,
                                 user: BackOfficeUser,
                                 event: BackOfficeEvent,
                                 `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {

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
    "alert"      -> "SessionDiscardedAlert",
    "user"       -> user.profile
  )
}

case class SessionsDiscardedAlert(`@id`: String,
                                  `@env`: String,
                                  user: BackOfficeUser,
                                  event: BackOfficeEvent,
                                  `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {

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
    "alert"      -> "SessionsDiscardedAlert",
    "user"       -> user.profile
  )
}

case class PanicModeAlert(`@id`: String,
                          `@env`: String,
                          user: BackOfficeUser,
                          event: BackOfficeEvent,
                          `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {

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
    "alert"      -> "PanicModeAlert",
    "user"       -> user.profile
  )
}

case class OtoroshiExportAlert(`@id`: String,
                               `@env`: String,
                               user: JsValue,
                               event: AdminApiEvent,
                               export: JsValue,
                               `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {

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
    "alert"      -> "OtoroshiExportAlert",
    "user"       -> user,
    "event"      -> event.toJson,
    "export"     -> export
  )
}

case class U2FAdminDeletedAlert(`@id`: String,
                                `@env`: String,
                                user: BackOfficeUser,
                                event: BackOfficeEvent,
                                `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {

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
    "alert"      -> "U2FAdminDeletedAlert",
    "event"      -> event.toJson,
    "user"       -> user.profile
  )
}

case class BlackListedBackOfficeUserAlert(`@id`: String,
                                          `@env`: String,
                                          user: BackOfficeUser,
                                          `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {

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
    "alert"      -> "BlackListedBackOfficeUserAlert",
    "user"       -> user.profile
  )
}

case class AdminLoggedInAlert(`@id`: String,
                              `@env`: String,
                              user: BackOfficeUser,
                              `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {

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
    "alert"        -> "AdminLoggedInAlert",
    "userName"     -> user.name,
    "userEmail"    -> user.email,
    "user"         -> user.profile,
    "userRandomId" -> user.randomId
  )
}

case class AdminFirstLogin(`@id`: String, `@env`: String, user: BackOfficeUser, `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {

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
    "alert"        -> "AdminFirstLogin",
    "userName"     -> user.name,
    "userEmail"    -> user.email,
    "user"         -> user.profile,
    "userRandomId" -> user.randomId
  )
}

case class AdminLoggedOutAlert(`@id`: String,
                               `@env`: String,
                               user: BackOfficeUser,
                               `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {

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
    "alert"        -> "AdminLoggedOutAlert",
    "userName"     -> user.name,
    "userEmail"    -> user.email,
    "user"         -> user.profile,
    "userRandomId" -> user.randomId
  )
}

case class DbResetAlert(`@id`: String, `@env`: String, user: JsValue, `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {

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
    "alert"      -> "DbResetAlert",
    "user"       -> user
  )
}

case class DangerZoneAccessAlert(`@id`: String, `@env`: String, user: JsValue, `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {

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
    "alert"      -> "DangerZoneAccessAlert",
    "user"       -> user
  )
}

case class GlobalConfigModification(`@id`: String,
                                    `@env`: String,
                                    user: JsValue,
                                    oldConfig: JsValue,
                                    newConfig: JsValue,
                                    audit: AuditEvent,
                                    `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {

  override def `@service`: String   = "Otoroshi"
  override def `@product`: String   = "opun"
  override def `@serviceId`: String = "--"

  override def toJson: JsValue = Json.obj(
    "@id"           -> `@id`,
    "@timestamp"    -> Writes.DefaultJodaDateWrites.writes(`@timestamp`),
    "@type"         -> `@type`,
    "@product"      -> `@product`,
    "@serviceId"    -> `@serviceId`,
    "@service"      -> `@service`,
    "@env"          -> `@env`,
    "alert"         -> "GlobalConfigModification",
    "adminApiAlert" -> true,
    "oldConfig"     -> oldConfig,
    "newConfig"     -> newConfig,
    "user"          -> user,
    "audit"         -> audit.toJson
  )
}

case class RevokedApiKeyUsageAlert(`@id`: String,
                                   `@timestamp`: DateTime,
                                   `@env`: String,
                                   req: HttpRequest,
                                   apiKey: ApiKey,
                                   descriptor: ServiceDescriptor)
    extends AlertEvent {

  override def `@service`: String   = descriptor.name
  override def `@product`: String   = descriptor.metadata.getOrElse("product", "--")
  override def `@serviceId`: String = descriptor.id

  override def toJson: JsValue = Json.obj(
    "@id"        -> `@id`,
    "@timestamp" -> Writes.DefaultJodaDateWrites.writes(`@timestamp`),
    "@type"      -> `@type`,
    "@product"   -> `@product`,
    "@serviceId" -> `@serviceId`,
    "@service"   -> `@service`,
    "@env"       -> `@env`,
    "alert"      -> "RevokedApiKeyUsageAlert",
    "from"       -> "0.0.0.0",
    "to"         -> req.uri.authority.host.address(),
    "uri"        -> req.uri.toString(),
    "apiKey"     -> apiKey.toJson
  )
}
case class ServiceGroupCreatedAlert(`@id`: String,
                                    `@env`: String,
                                    user: JsValue,
                                    audit: AuditEvent,
                                    `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {
  override def `@service`: String   = "Otoroshi"
  override def `@product`: String   = "opun"
  override def `@serviceId`: String = "--"
  override def toJson: JsValue = Json.obj(
    "@id"           -> `@id`,
    "@timestamp"    -> Writes.DefaultJodaDateWrites.writes(`@timestamp`),
    "@type"         -> `@type`,
    "@product"      -> `@product`,
    "@serviceId"    -> `@serviceId`,
    "@service"      -> `@service`,
    "@env"          -> `@env`,
    "alert"         -> "ServiceGroupCreatedAlert",
    "adminApiAlert" -> true,
    "user"          -> user,
    "audit"         -> audit.toJson
  )
}
case class ServiceGroupUpdatedAlert(`@id`: String,
                                    `@env`: String,
                                    user: JsValue,
                                    audit: AuditEvent,
                                    `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {
  override def `@service`: String   = "Otoroshi"
  override def `@product`: String   = "opun"
  override def `@serviceId`: String = "--"
  override def toJson: JsValue = Json.obj(
    "@id"           -> `@id`,
    "@timestamp"    -> Writes.DefaultJodaDateWrites.writes(`@timestamp`),
    "@type"         -> `@type`,
    "@product"      -> `@product`,
    "@serviceId"    -> `@serviceId`,
    "@service"      -> `@service`,
    "@env"          -> `@env`,
    "alert"         -> "ServiceGroupUpdatedAlert",
    "adminApiAlert" -> true,
    "user"          -> user,
    "audit"         -> audit.toJson
  )
}
case class ServiceGroupDeletedAlert(`@id`: String,
                                    `@env`: String,
                                    user: JsValue,
                                    audit: AuditEvent,
                                    `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {
  override def `@service`: String   = "Otoroshi"
  override def `@product`: String   = "opun"
  override def `@serviceId`: String = "--"
  override def toJson: JsValue = Json.obj(
    "@id"           -> `@id`,
    "@timestamp"    -> Writes.DefaultJodaDateWrites.writes(`@timestamp`),
    "@type"         -> `@type`,
    "@product"      -> `@product`,
    "@serviceId"    -> `@serviceId`,
    "@service"      -> `@service`,
    "@env"          -> `@env`,
    "alert"         -> "ServiceGroupDeletedAlert",
    "adminApiAlert" -> true,
    "user"          -> user,
    "audit"         -> audit.toJson
  )
}
case class ServiceCreatedAlert(`@id`: String,
                               `@env`: String,
                               user: JsValue,
                               audit: AuditEvent,
                               `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {
  override def `@service`: String   = "Otoroshi"
  override def `@product`: String   = "opun"
  override def `@serviceId`: String = "--"
  override def toJson: JsValue = Json.obj(
    "@id"           -> `@id`,
    "@timestamp"    -> Writes.DefaultJodaDateWrites.writes(`@timestamp`),
    "@type"         -> `@type`,
    "@product"      -> `@product`,
    "@serviceId"    -> `@serviceId`,
    "@service"      -> `@service`,
    "@env"          -> `@env`,
    "alert"         -> "ServiceCreatedAlert",
    "adminApiAlert" -> true,
    "user"          -> user,
    "audit"         -> audit.toJson
  )
}
case class ServiceUpdatedAlert(`@id`: String,
                               `@env`: String,
                               user: JsValue,
                               audit: AuditEvent,
                               `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {
  override def `@service`: String   = "Otoroshi"
  override def `@product`: String   = "opun"
  override def `@serviceId`: String = "--"
  override def toJson: JsValue = Json.obj(
    "@id"           -> `@id`,
    "@timestamp"    -> Writes.DefaultJodaDateWrites.writes(`@timestamp`),
    "@type"         -> `@type`,
    "@product"      -> `@product`,
    "@serviceId"    -> `@serviceId`,
    "@service"      -> `@service`,
    "@env"          -> `@env`,
    "alert"         -> "ServiceUpdatedAlert",
    "adminApiAlert" -> true,
    "user"          -> user,
    "audit"         -> audit.toJson
  )
}
case class ServiceDeletedAlert(`@id`: String,
                               `@env`: String,
                               user: JsValue,
                               audit: AuditEvent,
                               `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {
  override def `@service`: String   = "Otoroshi"
  override def `@product`: String   = "opun"
  override def `@serviceId`: String = "--"
  override def toJson: JsValue = Json.obj(
    "@id"           -> `@id`,
    "@timestamp"    -> Writes.DefaultJodaDateWrites.writes(`@timestamp`),
    "@type"         -> `@type`,
    "@product"      -> `@product`,
    "@serviceId"    -> `@serviceId`,
    "@service"      -> `@service`,
    "@env"          -> `@env`,
    "alert"         -> "ServiceDeletedAlert",
    "adminApiAlert" -> true,
    "user"          -> user,
    "audit"         -> audit.toJson
  )
}
case class ApiKeyCreatedAlert(`@id`: String,
                              `@env`: String,
                              user: JsValue,
                              audit: AuditEvent,
                              `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {
  override def `@service`: String   = "Otoroshi"
  override def `@product`: String   = "opun"
  override def `@serviceId`: String = "--"
  override def toJson: JsValue = Json.obj(
    "@id"           -> `@id`,
    "@timestamp"    -> Writes.DefaultJodaDateWrites.writes(`@timestamp`),
    "@type"         -> `@type`,
    "@product"      -> `@product`,
    "@serviceId"    -> `@serviceId`,
    "@service"      -> `@service`,
    "@env"          -> `@env`,
    "alert"         -> "ApiKeyCreatedAlert",
    "adminApiAlert" -> true,
    "user"          -> user,
    "audit"         -> audit.toJson
  )
}
case class ApiKeyUpdatedAlert(`@id`: String,
                              `@env`: String,
                              user: JsValue,
                              audit: AuditEvent,
                              `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {
  override def `@service`: String   = "Otoroshi"
  override def `@product`: String   = "opun"
  override def `@serviceId`: String = "--"
  override def toJson: JsValue = Json.obj(
    "@id"           -> `@id`,
    "@timestamp"    -> Writes.DefaultJodaDateWrites.writes(`@timestamp`),
    "@type"         -> `@type`,
    "@product"      -> `@product`,
    "@serviceId"    -> `@serviceId`,
    "@service"      -> `@service`,
    "@env"          -> `@env`,
    "alert"         -> "ApiKeyUpdatedAlert",
    "adminApiAlert" -> true,
    "user"          -> user,
    "audit"         -> audit.toJson
  )
}
case class ApiKeyDeletedAlert(`@id`: String,
                              `@env`: String,
                              user: JsValue,
                              audit: AuditEvent,
                              `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {
  override def `@service`: String   = "Otoroshi"
  override def `@product`: String   = "opun"
  override def `@serviceId`: String = "--"
  override def toJson: JsValue = Json.obj(
    "@id"           -> `@id`,
    "@timestamp"    -> Writes.DefaultJodaDateWrites.writes(`@timestamp`),
    "@type"         -> `@type`,
    "@product"      -> `@product`,
    "@serviceId"    -> `@serviceId`,
    "@service"      -> `@service`,
    "@env"          -> `@env`,
    "alert"         -> "ApiKeyDeletedAlert",
    "adminApiAlert" -> true,
    "identity"      -> user,
    "audit"         -> audit.toJson
  )
}

object AlertsActor {
  def props(implicit env: Env) = Props(new AlertsActor())
}

class AlertsActor(implicit env: Env) extends Actor {

  import org.joda.time.DateTime

  implicit val ec  = env.executionContext
  implicit val mat = env.materializer

  lazy val logger = Logger("otoroshi-alert-actor")

  lazy val kafkaWrapper = new KafkaWrapper(env.system, env, _.alertsTopic)

  lazy val emailStream = Source
    .queue[AlertEvent](5000, OverflowStrategy.dropHead)
    .groupedWithin(25, FiniteDuration(60, TimeUnit.SECONDS))
    .mapAsync(1) { evts =>
      val titles = evts
        .map(_.toJson)
        .map { jsonEvt =>
          val date = new DateTime((jsonEvt \ "@timestamp").as[Long])
          val id   = (jsonEvt \ "@id").as[String]
          s"""<li><a href="#$id">""" + (jsonEvt \ "alert")
            .asOpt[String]
            .getOrElse("Unkown alert") + s" - ${date.toString()}</a></li>"
        }
        .mkString("<ul>", "\n", "</ul>")

      val email = evts
        .map(_.toJson)
        .map { jsonEvt =>
          val alert   = (jsonEvt \ "alert").asOpt[String].getOrElse("Unkown alert")
          val message = (jsonEvt \ "audit" \ "message").asOpt[String].getOrElse("No description message")
          val date    = new DateTime((jsonEvt \ "@timestamp").as[Long])
          val id      = (jsonEvt \ "@id").as[String]
          s"""<h3 id="$id">$alert - ${date.toString()}</h3><pre>${Json.prettyPrint(jsonEvt)}</pre><br/>"""
        }
        .mkString("\n")

      val emailBody =
        s"""<p>${evts.size} new alerts occured on Otoroshi, you can visualize it on the <a href="${env.rootScheme}${env.backOfficeHost}/">Otoroshi Dashboard</a></p>
      |$titles
      |$email
    """.stripMargin

      env.datastores.globalConfigDataStore.singleton().flatMap { config =>
        config.mailGunSettings match {
          case Some(mailgunSettings) =>
            env.http
              .singleRequest(
                HttpRequest(method = HttpMethods.POST,
                            uri = s"https://api.mailgun.net/v3/${mailgunSettings.domain}/messages")
                  .addCredentials(HttpCredentials.createBasicHttpCredentials("api", mailgunSettings.apiKey))
                  .withEntity(
                    FormData(
                      Map(
                        "from"    -> s"Otoroshi Alerts <otoroshi@${env.domain}>",
                        "to"      -> config.alertsEmails.mkString(", "),
                        "subject" -> s"Otoroshi Alert - ${evts.size} new alerts",
                        "html"    -> emailBody
                      )
                    ).toEntity
                  )
              )
              .andThen {
                case Success(res) => logger.info("Alert email sent")
                case Failure(e)   => logger.error("Error while sending alert email", e)
              } map (_ => ())
          case _ => FastFuture.successful(())
        }
      }
    }

  lazy val stream = Source.queue[AlertEvent](50000, OverflowStrategy.dropHead).mapAsync(5) { evt =>
    for {
      r <- env.datastores.globalConfigDataStore.singleton().flatMap { config =>
            config.kafkaConfig.foreach { kafkaConfig =>
              kafkaWrapper.publish(evt.toJson)(env, kafkaConfig)
            }
            if (config.kafkaConfig.isEmpty) kafkaWrapper.close()
            Future.sequence(config.alertsWebhooks.map { webhook =>
              val url = webhook.url
              env.http
                .singleRequest(
                  HttpRequest(
                    uri = url,
                    headers = webhook.headers.toList.map(tuple => RawHeader(tuple._1, tuple._2)),
                    method = HttpMethods.POST,
                    entity =
                      HttpEntity(ContentTypes.`application/json`,
                                 ByteString(Json.stringify(Json.obj("event" -> "ALERT", "payload" -> evt.toJson))))
                  )
                )
                .andThen {
                  case Failure(e) => {
                    logger.error(s"Error while sending AlertEvent at '$url'", e)
                  }
                }
            })
          }
      _ <- env.datastores.alertDataStore.push(evt)
    } yield r
  }

  lazy val (alertQueue, alertDone) = stream.toMat(Sink.ignore)(Keep.both).run()(env.materializer)
  lazy val (emailQueue, emailDone) = emailStream.toMat(Sink.ignore)(Keep.both).run()(env.materializer)

  override def receive: Receive = {
    case ge: AlertEvent => {
      val myself = self
      alertQueue.offer(ge).andThen {
        case Success(QueueOfferResult.Dropped) => logger.error("Enqueue Dropped AlertEvent :(")
        case Success(QueueOfferResult.QueueClosed) =>
          logger.error("Queue closed AlertEvent :(")
          context.stop(myself)
        case Success(QueueOfferResult.Failure(t)) =>
          logger.error("Enqueue Failed AlertEvent :(", t)
          context.stop(myself)
      }
      emailQueue.offer(ge).andThen {
        case Success(QueueOfferResult.Dropped) => logger.error("Enqueue Dropped EmailAlertEvent :(")
        case Success(QueueOfferResult.QueueClosed) =>
          logger.error("Queue closed AlertEvent :(")
          context.stop(myself)
        case Success(QueueOfferResult.Failure(t)) =>
          logger.error("Enqueue Failed EmailAlertEvent :(", t)
          context.stop(myself)
      }
    }
    case _ =>
  }
}

class AlertsActorSupervizer(env: Env) extends Actor {

  lazy val childName = "alert-actor"
  lazy val logger    = Logger("otoroshi-alert-actor-supervizer")

  // override def supervisorStrategy: SupervisorStrategy =
  //   OneForOneStrategy() {
  //     case e =>
  //       Restart
  //   }

  override def receive: Receive = {
    case Terminated(ref) =>
      logger.warn("Restarting alert actor child")
      context.watch(context.actorOf(AlertsActor.props(env), childName))
    case evt => context.child(childName).map(_ ! evt)
  }

  override def preStart(): Unit =
    if (context.child(childName).isEmpty) {
      logger.info(s"Starting new child $childName")
      val ref = context.actorOf(AlertsActor.props(env), childName)
      context.watch(ref)
    }

  override def postStop(): Unit =
    context.children.foreach(_ ! PoisonPill)
}

object AlertsActorSupervizer {
  def props(implicit env: Env) = Props(new AlertsActorSupervizer(env))
}

object Alerts {

  lazy val logger = Logger("otoroshi-alerts")

  def send[A <: AlertEvent](alert: A)(implicit env: Env): Unit = {
    logger.info("Alert " + Json.stringify(alert.toJson))
    alert.toAnalytics()
    if (env.isProd) {
      env.alertsActor ! alert
    }
  }
}

trait AlertDataStore {
  def count()(implicit ec: ExecutionContext, env: Env): Future[Long]
  def findAllRaw(from: Long = 0, to: Long = 1000)(implicit ec: ExecutionContext, env: Env): Future[Seq[ByteString]]
  def push(event: AlertEvent)(implicit ec: ExecutionContext, env: Env): Future[Long]
}

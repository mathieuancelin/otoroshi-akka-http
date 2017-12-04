package io.otoroshi.events

import akka.Done
import akka.actor.{Actor, ActorSystem, Props}
import akka.http.scaladsl.util.FastFuture
import akka.kafka.ProducerSettings
import io.otoroshi.env.Env
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.producer.{Callback, KafkaProducer, ProducerRecord, RecordMetadata}
import org.apache.kafka.common.config.SslConfigs
import org.apache.kafka.common.serialization.{ByteArraySerializer, StringSerializer}
import play.api.libs.json._

import scala.concurrent.{Future, Promise}
import scala.util.Failure
import scala.util.control.NonFatal

case class KafkaConfig(servers: Seq[String],
                       keyPass: Option[String] = None,
                       keystore: Option[String] = None,
                       truststore: Option[String] = None,
                       alertsTopic: String = "otoroshi-alerts",
                       analyticsTopic: String = "otoroshi-analytics",
                       auditTopic: String = "otoroshi-audits")

object KafkaConfig {
  implicit val format = Json.format[KafkaConfig]
}

object KafkaSettings {

  def producerSettings(_env: Env, config: KafkaConfig): ProducerSettings[Array[Byte], String] = {
    val settings = ProducerSettings
      .create(_env.system, new ByteArraySerializer(), new StringSerializer())
      .withBootstrapServers(config.servers.mkString(","))

    val s = for {
      ks <- config.keystore
      ts <- config.truststore
      kp <- config.keyPass
    } yield {
      settings
        .withProperty(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SSL")
        .withProperty(SslConfigs.SSL_CLIENT_AUTH_CONFIG, "required")
        .withProperty(SslConfigs.SSL_KEY_PASSWORD_CONFIG, kp)
        .withProperty(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG, ks)
        .withProperty(SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG, kp)
        .withProperty(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, ts)
        .withProperty(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, kp)
    }

    s.getOrElse(settings)
  }
}

case class KafkaWrapperEvent(event: JsValue, env: Env, config: KafkaConfig)
case class KafkaWrapperEventClose()

class KafkaWrapper(actorSystem: ActorSystem, env: Env, topicFunction: KafkaConfig => String) {

  val kafkaWrapperActor = actorSystem.actorOf(KafkaWrapperActor.props(env, topicFunction))

  def publish(event: JsValue)(env: Env, config: KafkaConfig): Future[Done] = {
    kafkaWrapperActor ! KafkaWrapperEvent(event, env, config)
    FastFuture.successful(Done)
  }

  def close(): Unit = {
    kafkaWrapperActor ! KafkaWrapperEventClose()
  }
}

class KafkaWrapperActor(env: Env, topicFunction: KafkaConfig => String) extends Actor {

  implicit val ec = env.executionContext

  var config: Option[KafkaConfig]               = None
  var eventProducer: Option[KafkaEventProducer] = None

  lazy val logger = play.api.Logger("otoroshi-kafka-wrapper")

  override def receive: Receive = {
    case event: KafkaWrapperEvent if config.isEmpty && eventProducer.isEmpty => {
      config = Some(event.config)
      eventProducer.foreach(_.close())
      eventProducer = Some(new KafkaEventProducer(event.env, event.config, topicFunction))
      eventProducer.get.publish(event.event).andThen {
        case Failure(e) => logger.error("Error while pushing event to kafka", e)
      }
    }
    case event: KafkaWrapperEvent if config.isDefined && config.get != event.config => {
      config = Some(event.config)
      eventProducer.foreach(_.close())
      eventProducer = Some(new KafkaEventProducer(event.env, event.config, topicFunction))
      eventProducer.get.publish(event.event).andThen {
        case Failure(e) => logger.error("Error while pushing event to kafka", e)
      }
    }
    case event: KafkaWrapperEvent =>
      eventProducer.get.publish(event.event).andThen {
        case Failure(e) => logger.error("Error while pushing event to kafka", e)
      }
    case KafkaWrapperEventClose() =>
      eventProducer.foreach(_.close())
      config = None
      eventProducer = None
    case _ =>
  }
}

object KafkaWrapperActor {
  def props(env: Env, topicFunction: KafkaConfig => String) = Props(new KafkaWrapperActor(env, topicFunction))
}

class KafkaEventProducer(env: Env, config: KafkaConfig, topicFunction: KafkaConfig => String) {

  implicit val ec = env.executionContext

  lazy val logger = play.api.Logger("otoroshi-kafka-connector")

  lazy val topic = topicFunction(config)

  logger.info(s"Initializing kafka event store on topic ${topic}")

  private lazy val producerSettings                             = KafkaSettings.producerSettings(env, config)
  private lazy val producer: KafkaProducer[Array[Byte], String] = producerSettings.createKafkaProducer

  def publish(event: JsValue): Future[Done] = {
    val promise = Promise[RecordMetadata]
    try {
      val message = Json.stringify(event)
      producer.send(new ProducerRecord[Array[Byte], String](topic, message), callback(promise))
    } catch {
      case NonFatal(e) =>
        promise.failure(e)
    }
    promise.future.map { _ =>
      Done
    }
  }

  def close() =
    producer.close()

  private def callback(promise: Promise[RecordMetadata]) = new Callback {
    override def onCompletion(metadata: RecordMetadata, exception: Exception) =
      if (exception != null) {
        promise.failure(exception)
      } else {
        promise.success(metadata)
      }

  }
}

package io.otoroshi.storage.leveldb

import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import io.otoroshi.events.{AlertDataStore, AuditDataStore, HealthCheckDataStore}
import io.otoroshi.gateway.{InMemoryRequestsDataStore, RequestsDataStore}
import io.otoroshi.models._
import play.api.{Configuration, Logger}
import play.api.inject.ApplicationLifecycle
import io.otoroshi.storage.DataStores
import io.otoroshi.storage.inmemory._

import scala.concurrent.Future

class LevelDbDataStores(configuration: Configuration, lifecycle: ApplicationLifecycle) extends DataStores {

  lazy val logger = Logger("otoroshi-leveldb-datastores")

  lazy val dbPath: String       = configuration.getString("app.leveldb.path").getOrElse("./leveldb")
  lazy val redisStatsItems: Int = configuration.getInt("app.leveldb.windowSize").getOrElse(99)
  lazy val actorSystem =
    ActorSystem("leveldb", configuration.getConfig("app.leveldb.akka").getOrElse(Configuration.empty).underlying)
  lazy val redis = new LevelDbRedis(actorSystem, dbPath)

  override def before(configuration: Configuration, lifecycle: ApplicationLifecycle): Future[Unit] = {
    logger.warn("Now using LevelDB DataStores")
    redis.start()
    FastFuture.successful(())
  }

  override def after(configuration: Configuration, lifecycle: ApplicationLifecycle): Future[Unit] = {
    redis.stop()
    actorSystem.terminate()
    FastFuture.successful(())
  }

  private lazy val _privateAppsUserDataStore   = new InMemoryPrivateAppsUserDataStore(redis)
  private lazy val _backOfficeUserDataStore    = new InMemoryBackOfficeUserDataStore(redis)
  private lazy val _serviceGroupDataStore      = new InMemoryServiceGroupDataStore(redis)
  private lazy val _globalConfigDataStore      = new InMemoryGlobalConfigDataStore(redis)
  private lazy val _apiKeyDataStore            = new InMemoryApiKeyDataStore(redis)
  private lazy val _serviceDescriptorDataStore = new InMemoryServiceDescriptorDataStore(redis, redisStatsItems)
  private lazy val _u2FAdminDataStore          = new InMemoryU2FAdminDataStore(redis)
  private lazy val _simpleAdminDataStore       = new InMemorySimpleAdminDataStore(redis)
  private lazy val _alertDataStore             = new InMemoryAlertDataStore(redis)
  private lazy val _auditDataStore             = new InMemoryAuditDataStore(redis)
  private lazy val _healthCheckDataStore       = new InMemoryHealthCheckDataStore(redis)
  private lazy val _errorTemplateDataStore     = new InMemoryErrorTemplateDataStore(redis)
  private lazy val _requestsDataStore          = new InMemoryRequestsDataStore()
  private lazy val _canaryDataStore            = new InMemoryCanaryDataStore(redis)

  override def privateAppsUserDataStore: PrivateAppsUserDataStore     = _privateAppsUserDataStore
  override def backOfficeUserDataStore: BackOfficeUserDataStore       = _backOfficeUserDataStore
  override def serviceGroupDataStore: ServiceGroupDataStore           = _serviceGroupDataStore
  override def globalConfigDataStore: GlobalConfigDataStore           = _globalConfigDataStore
  override def apiKeyDataStore: ApiKeyDataStore                       = _apiKeyDataStore
  override def serviceDescriptorDataStore: ServiceDescriptorDataStore = _serviceDescriptorDataStore
  override def u2FAdminDataStore: U2FAdminDataStore                   = _u2FAdminDataStore
  override def simpleAdminDataStore: SimpleAdminDataStore             = _simpleAdminDataStore
  override def alertDataStore: AlertDataStore                         = _alertDataStore
  override def auditDataStore: AuditDataStore                         = _auditDataStore
  override def healthCheckDataStore: HealthCheckDataStore             = _healthCheckDataStore
  override def errorTemplateDataStore: ErrorTemplateDataStore         = _errorTemplateDataStore
  override def requestsDataStore: RequestsDataStore                   = _requestsDataStore
  override def canaryDataStore: CanaryDataStore                       = _canaryDataStore
}

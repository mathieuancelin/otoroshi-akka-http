package io.otoroshi.env

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{Executors, ThreadFactory, TimeUnit}

import akka.actor.{ActorSystem, PoisonPill}
import akka.http.scaladsl.HttpExt
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.headers.{HttpCookie, RawHeader}
import akka.http.scaladsl.util.FastFuture
import akka.stream.ActorMaterializer
import akka.util.ByteString
import com.codahale.metrics.MetricRegistry
import io.otoroshi.events._
import io.otoroshi.gateway.CircuitBreakersHolder
import io.otoroshi.models._
import io.otoroshi.security.{ClaimCrypto, IdGenerator}
import io.otoroshi.storage.DataStores
import io.otoroshi.storage.cassandra.CassandraDataStores
import io.otoroshi.storage.inmemory.InMemoryDataStores
import io.otoroshi.storage.leveldb.LevelDbDataStores
import io.otoroshi.storage.redis.RedisDataStores
import org.mindrot.jbcrypt.BCrypt
import play.api._
import play.api.inject.ApplicationLifecycle
import play.api.libs.json.{JsObject, Json}

import scala.collection.JavaConversions._
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.io.Source
import scala.util.Success

class Env(val metrics: MetricRegistry,
          val system: ActorSystem,
          val executionContext: ExecutionContext,
          val materializer: ActorMaterializer,
          val http: HttpExt,
          val configuration: Configuration,
          val lifecycle: ApplicationLifecycle,
          val circuitBeakersHolder: CircuitBreakersHolder) {

  private lazy val scheduler = Executors.newScheduledThreadPool(procNbr * 2)

  lazy val logger = Logger("otoroshi-env")

  def timeout(duration: FiniteDuration): Future[Unit] = {
    val promise = Promise[Unit]
    scheduler.schedule(new Runnable {
      override def run(): Unit = promise.trySuccess(())
    }, duration.toMillis, TimeUnit.MILLISECONDS)
    promise.future
  }

  val analyticsActor = system.actorOf(AnalyticsActorSupervizer.props(this))
  val alertsActor    = system.actorOf(AlertsActorSupervizer.props(this))

  lazy val maxWebhookSize: Int = configuration.getInt("app.webhooks.size").getOrElse(100)

  lazy val useRedisScan: Boolean          = configuration.getBoolean("app.redis.useScan").getOrElse(false)
  lazy val commitId: String               = configuration.getString("app.commitId").getOrElse("HEAD")
  lazy val secret: String                 = configuration.getString("play.crypto.secret").get
  lazy val sharedKey: String              = configuration.getString("app.claim.sharedKey").get
  lazy val env: String                    = configuration.getString("app.env").getOrElse("prod")
  lazy val adminApiProxyHttps: Boolean    = configuration.getBoolean("app.adminapi.proxy.https").getOrElse(false)
  lazy val adminApiProxyUseLocal: Boolean = configuration.getBoolean("app.adminapi.proxy.local").getOrElse(true)
  lazy val redirectToDev: Boolean = env
    .toLowerCase() == "dev" && configuration.getBoolean("app.redirectToDev").getOrElse(false)
  lazy val envInUrl: String = configuration.getString("app.env").filterNot(_ == "prod").map(v => s"$v.").getOrElse("")
  lazy val domain: String   = configuration.getString("app.domain").getOrElse("foo.bar")
  lazy val adminApiSubDomain: String =
    configuration.getString("app.adminapi.targetSubdomain").getOrElse("otoroshi-admin-internal-api")
  lazy val adminApiExposedSubDomain: String =
    configuration.getString("app.adminapi.exposedDubdomain").getOrElse("otoroshi-api")
  lazy val backOfficeSubDomain: String  = configuration.getString("app.backoffice.subdomain").getOrElse("otoroshi")
  lazy val privateAppsSubDomain: String = configuration.getString("app.privateapps.subdomain").getOrElse("privateapps")
  lazy val retries: Int                 = configuration.getInt("app.retries").getOrElse(5)

  lazy val backOfficeServiceId      = configuration.getString("app.adminapi.defaultValues.backOfficeServiceId").get
  lazy val backOfficeGroupId        = configuration.getString("app.adminapi.defaultValues.backOfficeGroupId").get
  lazy val backOfficeApiKeyClientId = configuration.getString("app.adminapi.defaultValues.backOfficeApiKeyClientId").get
  lazy val backOfficeApiKeyClientSecret =
    configuration.getString("app.adminapi.defaultValues.backOfficeApiKeyClientSecret").get

  def composeUrl(subdomain: String): String     = s"$subdomain.$envInUrl$domain"
  def composeMainUrl(subdomain: String): String = if (isDev) composeUrl(subdomain) else s"$subdomain.$domain"
  // def composeMainUrl(subdomain: String): String = composeUrl(subdomain)

  lazy val adminApiExposedHost = composeMainUrl(adminApiExposedSubDomain)
  lazy val adminApiHost        = composeMainUrl(adminApiSubDomain)
  lazy val backOfficeHost      = composeMainUrl(backOfficeSubDomain)
  lazy val privateAppsHost     = composeMainUrl(privateAppsSubDomain)

  lazy val procNbr = Runtime.getRuntime.availableProcessors()

  //lazy val kafkaActorSytem  = ActorSystem("kafka-actor-system")
  //lazy val statsdActorSytem = ActorSystem("statsd-actor-system")

  lazy val statsd = new StatsdWrapper(system, this)

  lazy val mode = env match {
    case "dev"     => play.api.Mode.Dev
    case "test"    => play.api.Mode.Test
    case "prod"    => play.api.Mode.Prod
    case "preprod" => play.api.Mode.Prod
  }
  lazy val isDev  = mode == Mode.Dev
  lazy val isProd = !isDev
  lazy val notDev = !isDev
  lazy val hash   = s"${System.currentTimeMillis()}"

  lazy val privateAppsSessionExp = configuration.getLong("app.privateapps.session.exp").get
  lazy val backOfficeSessionExp  = configuration.getLong("app.backoffice.session.exp").get

  lazy val exposedRootScheme = configuration.getString("app.rootScheme").getOrElse("https")

  def rootScheme               = if (isDev) "http://" else s"${exposedRootScheme}://"
  def exposedRootSchemeIsHttps = exposedRootScheme == "https"

  lazy val snowflakeSeed      = configuration.getLong("app.snowflake.seed").get
  lazy val snowflakeGenerator = IdGenerator(snowflakeSeed)
  lazy val redirections: Seq[String] =
    configuration.getStringList("app.redirections").map(_.toSeq).getOrElse(Seq.empty[String])

  lazy val crypto = ClaimCrypto(sharedKey)

  object Headers {
    lazy val OpunVizFromLabel               = configuration.getString("otoroshi.headers.trace.label").get
    lazy val OpunVizFrom                    = configuration.getString("otoroshi.headers.trace.from").get
    lazy val OpunGatewayParentRequest       = configuration.getString("otoroshi.headers.trace.parent").get
    lazy val OpunAdminProfile               = configuration.getString("otoroshi.headers.request.adminprofile").get
    lazy val OpunClientId                   = configuration.getString("otoroshi.headers.request.clientid").get
    lazy val OpunClientSecret               = configuration.getString("otoroshi.headers.request.clientsecret").get
    lazy val OpunGatewayRequestId           = configuration.getString("otoroshi.headers.request.id").get
    lazy val OpunProxiedHost                = configuration.getString("otoroshi.headers.response.proxyhost").get
    lazy val OpunGatewayError               = configuration.getString("otoroshi.headers.response.error").get
    lazy val OpunGatewayErrorMsg            = configuration.getString("otoroshi.headers.response.errormsg").get
    lazy val OpunGatewayProxyLatency        = configuration.getString("otoroshi.headers.response.proxylatency").get
    lazy val OpunGatewayUpstreamLatency     = configuration.getString("otoroshi.headers.response.upstreamlatency").get
    lazy val OpunDailyCallsRemaining        = configuration.getString("otoroshi.headers.response.dailyquota").get
    lazy val OpunMonthlyCallsRemaining      = configuration.getString("otoroshi.headers.response.monthlyquota").get
    lazy val OpunGatewayState               = configuration.getString("otoroshi.headers.comm.state").get
    lazy val OpunGatewayStateResp           = configuration.getString("otoroshi.headers.comm.stateresp").get
    lazy val OpunGatewayClaim               = configuration.getString("otoroshi.headers.comm.claim").get
    lazy val OpunHealthCheckLogicTest       = configuration.getString("otoroshi.headers.healthcheck.test").get
    lazy val OpunHealthCheckLogicTestResult = configuration.getString("otoroshi.headers.healthcheck.testresult").get
    lazy val OpunGateway                    = configuration.getString("otoroshi.headers.jwt.issuer").get
    lazy val OpunTrackerId                  = configuration.getString("otoroshi.headers.canary.tracker").get
  }

  private def factory(of: String) = new ThreadFactory {
    val counter                                 = new AtomicInteger(0)
    override def newThread(r: Runnable): Thread = new Thread(r, s"$of-${counter.incrementAndGet()}")
  }

  lazy val datastores: DataStores = {
    configuration.getString("app.storage").getOrElse("redis") match {
      case "redis"     => new RedisDataStores(configuration, lifecycle)
      case "inmemory"  => new InMemoryDataStores(this, configuration, lifecycle)
      case "leveldb"   => new LevelDbDataStores(configuration, lifecycle)
      case "cassandra" => new CassandraDataStores(configuration, lifecycle)
      case e           => throw new RuntimeException(s"Bad storage value from conf: $e")
    }
  }

  datastores.before(configuration, lifecycle)
  lifecycle.addStopHook(() => {
    analyticsActor ! PoisonPill
    alertsActor ! PoisonPill
    //kafkaActorSytem.terminate()
    //statsdActorSytem.terminate()
    datastores.after(configuration, lifecycle)
    FastFuture.successful(())
  })

  lazy val port =
    configuration.getInt("play.server.http.port").orElse(configuration.getInt("http.port")).getOrElse(6000)

  lazy val defaultConfig = GlobalConfig(
    perIpThrottlingQuota = 500,
    throttlingQuota = 100000
  )

  lazy val backOfficeGroup = ServiceGroup(
    id = backOfficeGroupId,
    name = "Otoroshi Admin Api group"
  )

  lazy val backOfficeApiKey = ApiKey(
    backOfficeApiKeyClientId,
    backOfficeApiKeyClientSecret,
    "Otoroshi Backoffice ApiKey",
    backOfficeGroupId
  )

  private lazy val backOfficeDescriptorHostHeader: String =
    if (isDev) s"$adminApiSubDomain.dev.$domain" else s"$adminApiSubDomain.$domain"

  lazy val backOfficeDescriptor = ServiceDescriptor(
    id = backOfficeServiceId,
    groupId = backOfficeGroupId,
    name = "otoroshi-admin-api",
    env = "prod",
    subdomain = adminApiExposedSubDomain,
    domain = domain,
    targets = Seq(
      Target(
        host = s"127.0.0.1:$port",
        scheme = exposedRootScheme
      )
    ),
    redirectToLocal = isDev,
    localHost = s"127.0.0.1:$port",
    forceHttps = false,
    additionalHeaders = Map(
      "Host" -> backOfficeDescriptorHostHeader
    )
  )

  def start(): Future[Boolean] = {
    implicit val ec  = executionContext
    implicit val mat = materializer

    import collection.JavaConversions._

    datastores.globalConfigDataStore.isOtoroshiEmpty().andThen {
      case Success(true) => {
        logger.warn(s"The main datastore seems to be empty, registering some basic services")
        val password = IdGenerator.token(32)
        val headers: Seq[(String, String)] = configuration
          .getStringList("app.importFromHeaders")
          .map(headers => headers.toSeq.map(h => h.split(":")).map(h => (h(0).trim, h(1).trim)))
          .getOrElse(Seq.empty[(String, String)])
        configuration.getString("app.importFrom") match {
          case Some(url) if url.startsWith("http://") || url.startsWith("https://") => {
            logger.warn(s"Importing from URL: $url")
            val akkaHeaders = headers.map(tuple => RawHeader(tuple._1, tuple._2)).toList
            http
              .singleRequest(HttpRequest(uri = url, headers = akkaHeaders))
              .flatMap(resp => resp.entity.dataBytes.runFold(ByteString(""))(_ ++ _))
              .map(body => Json.parse(body.utf8String).as[JsObject])
              .map(json => datastores.globalConfigDataStore.fullImport(json)(ec, this))
          }
          case Some(path) => {
            logger.warn(s"Importing from: $path")
            val source = Source.fromFile(path).getLines().mkString("\n")
            val json   = Json.parse(source).as[JsObject]
            datastores.globalConfigDataStore.fullImport(json)(ec, this)
          }
          case _ => {
            val defaultGroup = ServiceGroup("default", "default-group", "The default service group")
            val defaultGroupApiKey = ApiKey("9HFCzZIPUQQvfxkq",
                                            "lmwAGwqtJJM7nOMGKwSAdOjC3CZExfYC7qXd4aPmmseaShkEccAnmpULvgnrt6tp",
                                            "default-apikey",
                                            "default")
            logger.warn(
              s"You can log into the Otoroshi admin console with the following credentials: admin@otoroshi.io / $password"
            )
            for {
              _ <- defaultConfig.save()(ec, this)
              _ <- backOfficeGroup.save()(ec, this)
              _ <- defaultGroup.save()(ec, this)
              _ <- backOfficeDescriptor.save()(ec, this)
              _ <- backOfficeApiKey.save()(ec, this)
              _ <- defaultGroupApiKey.save()(ec, this)
              _ <- datastores.simpleAdminDataStore.registerUser("admin@otoroshi.io",
                                                                BCrypt.hashpw(password, BCrypt.gensalt()),
                                                                "Otoroshi Admin")(ec, this)
            } yield ()
          }
        }
      }
    }
  }

  /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  lazy val sessionDomain = configuration.getString("play.http.session.domain").get
  lazy val sessionMaxAge = configuration.getInt("play.http.session.maxAge").getOrElse(86400)
  lazy val playSecret    = configuration.getString("play.crypto.secret").get

  def sign(message: String): String =
    scala.util.Try {
      val mac = javax.crypto.Mac.getInstance("HmacSHA256")
      mac.init(new javax.crypto.spec.SecretKeySpec(playSecret.getBytes("utf-8"), "HmacSHA256"))
      org.apache.commons.codec.binary.Hex.encodeHexString(mac.doFinal(message.getBytes("utf-8")))
    } match {
      case scala.util.Success(s) => s
      case scala.util.Failure(e) => {
        logger.error(s"Error while signing: ${message}", e)
        throw e
      }
    }

  def extractPrivateSessionId(cookie: HttpCookie): Option[String] =
    cookie.value.split("::").toList match {
      case signature :: value :: Nil if sign(value) == signature => Some(value)
      case _                                                     => None
    }

  def signPrivateSessionId(id: String): String = {
    val signature = sign(id)
    s"$signature::$id"
  }

  def createPrivateSessionCookies(host: String, id: String): Seq[HttpCookie] =
    if (host.endsWith(sessionDomain)) {
      Seq(
        HttpCookie(
          name = "oto-papps",
          value = signPrivateSessionId(id),
          maxAge = Some(sessionMaxAge),
          path = Some("/"),
          domain = Some(sessionDomain),
          httpOnly = false
        )
      )
    } else {
      Seq(
        HttpCookie(
          name = "oto-papps",
          value = signPrivateSessionId(id),
          maxAge = Some(sessionMaxAge),
          path = Some("/"),
          domain = Some(host),
          httpOnly = false
        ),
        HttpCookie(
          name = "oto-papps",
          value = signPrivateSessionId(id),
          maxAge = Some(sessionMaxAge),
          path = Some("/"),
          domain = Some(sessionDomain),
          httpOnly = false
        )
      )
    }

  def removePrivateSessionCookies(host: String): Seq[HttpCookie] =
    Seq(
      HttpCookie(
        name = "oto-papps",
        value = "",
        path = Some("/"),
        domain = Some(host),
        maxAge = Some(-86400)
      ),
      HttpCookie(
        name = "oto-papps",
        value = "",
        path = Some("/"),
        domain = Some(sessionDomain),
        maxAge = Some(-86400)
      )
    )
}

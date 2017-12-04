package io.otoroshi

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.model.headers.Host
import akka.http.scaladsl.model._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Compression, Flow}
import akka.util.ByteString
import com.codahale.metrics.{ConsoleReporter, JmxReporter, MetricRegistry}
import com.softwaremill.macwire._
import com.typesafe.config.{Config, ConfigFactory}
import io.otoroshi.env._
import io.otoroshi.gateway._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.util.FastFuture
import org.slf4j.LoggerFactory
import play.api.Configuration
import play.api.inject.ApplicationLifecycle
import play.api.libs.json.Json
import play.api.mvc.Results.Redirect
import utils.functionaljava.Implicits._
import utils.akkahttp.Implicits._

import scala.concurrent.Future
import scala.util.Success

class Otoroshi(otoroshiHost: String, otoroshiPort: Int, conf: Config) {

  private[otoroshi] val logger = LoggerFactory.getLogger("otoroshi")

  private[otoroshi] implicit val system           = ActorSystem("otoroshi-system", conf)
  private[otoroshi] implicit val materializer     = ActorMaterializer()
  private[otoroshi] implicit val executionContext = system.dispatcher
  private[otoroshi] implicit val http             = Http()

  private[otoroshi] val metrics = new MetricRegistry()
  private[otoroshi] val counter = new AtomicLong(0L)

  private[otoroshi] val consoleReporter = ConsoleReporter
    .forRegistry(metrics)
    .convertRatesTo(TimeUnit.SECONDS)
    .convertDurationsTo(TimeUnit.MILLISECONDS)
    .build()

  private[otoroshi] val jmxReporter = JmxReporter
    .forRegistry(metrics)
    .convertRatesTo(TimeUnit.SECONDS)
    .convertDurationsTo(TimeUnit.MILLISECONDS)
    .build()

  private[otoroshi] val secure                     = false // TODO : yeahhhh
  private[otoroshi] val configuration              = new Configuration(conf)
  private[otoroshi] lazy val applicationLifecycle  = wire[ApplicationLifecycle]
  private[otoroshi] lazy val circuitBreakersHolder = wire[CircuitBreakersHolder]
  private[otoroshi] implicit lazy val env          = wire[Env]
  private[otoroshi] lazy val httpRequestHandler    = wire[GatewayRequestHandler]
  private[otoroshi] lazy val httpErrorHandler      = wire[ErrorHandler]
  private[otoroshi] lazy val apiController         = wire[ApiController]

  private[otoroshi] def matchRedirection(host: String): Boolean = {
    env.redirections.nonEmpty && env.redirections.exists(it => host.contains(it))
  }

  private[otoroshi] def decodeRequestWith(decoderFlow: Flow[ByteString, ByteString, NotUsed],
                                          request: HttpRequest): HttpRequest = {
    request
      .withEntity(request.entity.transformDataBytes(decoderFlow))
      .withHeaders(request.headers.filterNot(_.isInstanceOf[`Content-Encoding`]))
  }

  // TODO : use it ?
  private[otoroshi] def decodeRequest(request: HttpRequest): HttpRequest = {
    request.encoding match {
      case HttpEncodings.gzip    => decodeRequestWith(Compression.gunzip(), request)
      case HttpEncodings.deflate => decodeRequestWith(Compression.inflate(), request)
      // Handle every undefined decoding as is
      case _ => request
    }
  }

  private[otoroshi] def redirectToMainDomain(request: HttpRequest,
                                             reqUri: String,
                                             reqDomain: String,
                                             reqProtocol: String) = {
    val domain: String = env.redirections.foldLeft(reqDomain)((domain, item) => domain.replace(item, env.domain))
    logger.warn(s"redirectToMainDomain from $reqProtocol://$reqProtocol$reqUri to $reqProtocol://$domain$reqUri")
    FastFuture.successful(Redirect(s"$reqProtocol://$domain$reqUri").underlying)
  }

  private[otoroshi] def redirectToHttps(request: HttpRequest,
                                        reqUri: String,
                                        reqDomain: String,
                                        reqProtocol: String) = {
    logger.info(
      s"redirectToHttps from $reqProtocol://$reqDomain$reqUri to ${env.rootScheme}$reqDomain$reqUri"
    )
    FastFuture.successful(
      Redirect(s"${env.rootScheme}$reqDomain$reqUri").withHeaders("opun-redirect-to" -> "https").underlying
    )
  }

  private[otoroshi] def notImplementedYet(request: HttpRequest): Future[HttpResponse] = {
    request.discardEntityBytes()
    Future.successful {
      HttpResponse(
        500,
        entity = HttpEntity(ContentTypes.`application/json`, Json.stringify(Json.obj("error" -> "Not implemented yet")))
      )
    }
  }

  private[otoroshi] def handler(request: HttpRequest): Future[HttpResponse] = {
    val _t1 = metrics.timer("request_duration").time()
    val _t2 = metrics.timer("router_duration").time()

    lazy val isSecured = request.isSecured(secure)
    lazy val protocol  = request.scheme(secure)
    lazy val toHttps   = env.exposedRootSchemeIsHttps
    lazy val uri       = request.uriString
    lazy val host      = request.host
    lazy val domain    = request.domain

    // TODO handle websockets
    val fu = host match {
      case str if matchRedirection(str)                               => redirectToMainDomain(request, uri, domain, protocol)
      case _ if uri.startsWith("/assets")                             => notImplementedYet(request)
      case _ if uri.startsWith("/__opun_assets")                      => notImplementedYet(request)
      case _ if uri.startsWith("/__otoroshi_private_apps_login")      => notImplementedYet(request)
      case _ if uri.startsWith("/__otoroshi_private_apps_logout")     => notImplementedYet(request)
      case env.backOfficeHost if !isSecured && toHttps && env.isProd  => redirectToHttps(request, uri, domain, protocol)
      case env.privateAppsHost if !isSecured && toHttps && env.isProd => redirectToHttps(request, uri, domain, protocol)
      case env.adminApiHost =>
        request match {
          case HttpRequest(HttpMethods.GET, Uri.Path("/api/live") , _, _, _) => apiController.globalLiveStats()
          case _                                                            => notImplementedYet(request)
        }
      case env.backOfficeHost  => notImplementedYet(request)
      case env.privateAppsHost => notImplementedYet(request)
      case _ => {
        val _t3 = metrics.timer("proxy_duration").time()
        httpRequestHandler.forwardCall(request).andThen {
          case _ => _t3.stop()
        }
      }
    }
    _t2.stop()
    fu.andThen {
      case _ => _t1.stop()
    }
  }

  def start(): RunningOtoroshi = {
    new RunningOtoroshi(
      env.start().flatMap { _ =>
        http.bindAndHandleAsync(handler, otoroshiHost, otoroshiPort).andThen {
          case Success(_) =>
            logger.warn(s"Otoroshi listening at http://$otoroshiHost:$otoroshiPort 👹")
            jmxReporter.start()
        }
      },
      this
    )
  }
}

class RunningOtoroshi(bindingFuture: Future[ServerBinding], otoroshi: Otoroshi) {
  def stop(): Future[Unit] = {
    bindingFuture
      .flatMap(_.unbind())(otoroshi.executionContext)
      .andThen {
        case _ =>
          otoroshi.applicationLifecycle.stop()(otoroshi.executionContext)
          otoroshi.jmxReporter.stop()
          otoroshi.system.terminate()
          otoroshi.logger.info("Otoroshi server died \uD83D\uDE1F")
      }(otoroshi.executionContext)
  }
}

object Main {

  val config = ConfigFactory.load()

  def main(args: Array[String]) {
    val port     = Option(System.getenv("PORT")).map(_.toInt).getOrElse(8080)
    val otoroshi = new Otoroshi("0.0.0.0", port, config).start()
    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      otoroshi.stop()
    }))
  }
}

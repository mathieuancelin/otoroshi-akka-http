package io.otoroshi.gateway

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicLong}

import akka.http.scaladsl.model.Uri.Authority
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.codahale.metrics.MetricRegistry
import com.google.common.base.Charsets
import io.otoroshi.env.Env
import io.otoroshi.events._
import io.otoroshi.events.{Location => EventLocation}
import io.otoroshi.models._
import io.otoroshi.security.{IdGenerator, OpunClaim}
import io.otoroshi.utils.RegexPool
import org.joda.time.DateTime
import play.api.Logger
import play.api.mvc.Results
import play.api.mvc.Results._

import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success, Try}
import io.otoroshi.utils.akkahttp.Implicits._

case class ProxyDone(status: Int, upstreamLatency: Long)

class ErrorHandler()(implicit env: Env) {

  implicit val ec = env.executionContext

  lazy val logger = Logger("otoroshi-error-handler")

  def onClientError(request: HttpRequest, statusCode: Int, mess: String) = {
    val (message, image): (String, String) = Option
      .apply(mess)
      .filterNot(_.trim.isEmpty)
      // .map(m => (m, "hugeMistake.jpg"))
      .map(m => ("An error occurred ...", "hugeMistake.jpg"))
      .orElse(Errors.messages.get(statusCode))
      .getOrElse(("Client Error", "hugeMistake.jpg"))
    logger.error(s"Client Error: $message on ${request.uri} ($statusCode)")
    Errors.craftResponseResult(message, Status(statusCode), request, None, Some("errors.client.error"))
  }

  def onServerError(request: HttpRequest, exception: Throwable) = {
    logger.error(s"Server Error ${exception.getMessage} on ${request.uri}", exception)
    Errors.craftResponseResult("An error occurred ...", InternalServerError, request, None, Some("errors.server.error"))
  }
}

class GatewayRequestHandler(secure: Boolean, metrics: MetricRegistry)(implicit env: Env, mat: Materializer) {

  implicit lazy val ec        = env.executionContext
  implicit lazy val scheduler = env.system.scheduler

  lazy val logger = Logger("otoroshi-http-handler")

  val reqCounter = new AtomicInteger(0)

  val headersInFiltered = Seq(
    env.Headers.OpunGatewayState,
    env.Headers.OpunGatewayClaim,
    env.Headers.OpunGatewayRequestId,
    "Host",
    "Remote-Address",
    "Timeout-Access"
  ).map(_.toLowerCase)

  val headersOutFiltered = Seq(
    env.Headers.OpunGatewayStateResp,
    "Transfer-Encoding",
    "Content-Length"
  ).map(_.toLowerCase)

  def hasBody(request: HttpRequest): Boolean = {
    (request.method, request.header[`Content-Length`]) match {
      case (HttpMethods.GET, Some(_))    => true
      case (HttpMethods.GET, None)       => false
      case (HttpMethods.HEAD, Some(_))   => true
      case (HttpMethods.HEAD, None)      => false
      case (HttpMethods.PATCH, _)        => true
      case (HttpMethods.POST, _)         => true
      case (HttpMethods.PUT, _)          => true
      case (HttpMethods.DELETE, Some(_)) => true
      case (HttpMethods.DELETE, None)    => false
      case _                             => true
    }
    request.entity.isKnownEmpty()
  }

  def isPrivateAppsSessionValid(req: HttpRequest): Future[Option[PrivateAppsUser]] =
    req.cookies
      .find(_.name == "oto-papps")
      .map(_.toCookie())
      .flatMap { cookie =>
        env.extractPrivateSessionId(cookie)
      }
      .map { id =>
        env.datastores.privateAppsUserDataStore.findById(id)
      } getOrElse {
      FastFuture.successful(None)
    }

  def splitToCanary(desc: ServiceDescriptor, trackingId: String)(implicit env: Env): Future[ServiceDescriptor] =
    if (desc.canary.enabled) {
      env.datastores.canaryDataStore.isCanary(desc.id, trackingId, desc.canary.traffic).map {
        case false => desc
        case true  => desc.copy(targets = desc.canary.targets, root = desc.canary.root)
      }
    } else {
      FastFuture.successful(desc)
    }

  def forwardCall(req: HttpRequest): Future[HttpResponse] = {
    val _t99                = metrics.timer("proxy_overhead_duration").time()
    val reqUri              = req.uriString
    val reqHost             = req.host
    val reqDomain           = req.domain
    val remoteAddress       = req.remoteAddress
    val isSecured           = req.isSecured(secure)
    val from                = remoteAddress
    //val counterIn           = new AtomicLong(0L)
    //val counterOut          = new AtomicLong(0L)
    val start               = System.currentTimeMillis()
    val bodyAlreadyConsumed = new AtomicBoolean(false)
    val protocol            = req.scheme(secure)

    val currentHandledRequests = env.datastores.requestsDataStore.incrementHandledRequests()
    val _t2                    = metrics.timer("global_config_fetch_duration").time()
    val finalResult: Future[HttpResponse] =
      env.datastores.globalConfigDataStore.singleton().flatMap { globalConfig => // Very consuming but eh !!!
        _t2.close()
        env.statsd.meter(s"${env.snowflakeSeed}.concurrent-requests", currentHandledRequests.toDouble)(
          globalConfig.statsdConfig
        )
        if (currentHandledRequests > globalConfig.maxConcurrentRequests) {
          Audit.send(
            MaxConcurrentRequestReachedEvent(
              env.snowflakeGenerator.nextId().toString,
              env.env,
              globalConfig.maxConcurrentRequests,
              currentHandledRequests
            )
          )
          Alerts.send(
            MaxConcurrentRequestReachedAlert(
              env.snowflakeGenerator.nextId().toString,
              env.env,
              globalConfig.maxConcurrentRequests,
              currentHandledRequests
            )
          )
        }
        if (globalConfig.limitConcurrentRequests && currentHandledRequests > globalConfig.maxConcurrentRequests) {
          Errors
            .craftResponseResult(s"Cannot process more request",
                                 BadGateway,
                                 req,
                                 None,
                                 Some("errors.cant.process.more.request"))
            .map(_.underlying)
        } else {
          val _t3 = metrics.timer("compute_service_location_duration").time()
          ServiceLocation(reqHost, globalConfig) match {
            case None =>
              _t3.close()
              Errors
                .craftResponseResult(s"Service not found for URL $reqHost::${reqUri}",
                                     NotFound,
                                     req,
                                     None,
                                     Some("errors.service.not.found"))
                .map(_.underlying)
            case Some(ServiceLocation(domain, serviceEnv, subdomain)) => {
              _t3.close()
              val uriParts = reqUri.split("/").toSeq
              val root     = if (uriParts.isEmpty) "/" else "/" + uriParts.tail.head
              val _t4      = metrics.timer("find_service_duration").time()
              env.datastores.serviceDescriptorDataStore
                .find(
                  ServiceDescriptorQuery(subdomain,
                                         serviceEnv,
                                         domain,
                                         root,
                                         req.headers.map(h => (h.name(), h.value())).toMap)
                )
                .flatMap {
                  case None =>
                    _t4.stop()
                    Errors
                      .craftResponseResult(s"Downstream service not found",
                                           NotFound,
                                           req,
                                           None,
                                           Some("errors.service.not.found"))
                      .map(_.underlying)
                  case Some(desc) if !desc.enabled =>
                    _t4.stop()
                    Errors
                      .craftResponseResult(s"Service not found", NotFound, req, None, Some("errors.service.not.found"))
                      .map(_.underlying)
                  case Some(desc) => {
                    _t4.stop()
                    val _t5 = metrics.timer("service_found_overhead_duration").time()
                    val maybeTrackingId = req.cookies
                      .find(_.name == "otoroshi-canary")
                      .map(_.toCookie().value)
                      .orElse(req.headerValue(env.Headers.OpunTrackerId))
                      .filter { value =>
                        if (value.contains("::")) {
                          value.split("::").toList match {
                            case signed :: id :: Nil if env.sign(id) == signed => true
                            case _                                             => false
                          }
                        } else {
                          false
                        }
                      } map (value => value.split("::")(1))
                    val trackingId: String = maybeTrackingId.getOrElse(IdGenerator.uuid)

                    if (maybeTrackingId.isDefined) {
                      logger.debug(s"request already has tracking id : $trackingId")
                    } else {
                      logger.debug(s"request has a new tracking id : $trackingId")
                    }

                    val withTrackingCookies =
                      if (maybeTrackingId.isDefined) Seq.empty[HttpCookie]
                      else
                        Seq(
                          HttpCookie(
                            name = "otoroshi-canary",
                            value = s"${env.sign(trackingId)}::$trackingId",
                            maxAge = Some(2592000),
                            path = Some("/"),
                            domain = Some(reqHost),
                            httpOnly = false
                          )
                        )
                    _t5.stop()
                    val _t6 = metrics.timer("call_canary_split_duration").time()

                    desc.isUp.flatMap(iu => splitToCanary(desc, trackingId).map(d => (iu, d))).flatMap { tuple =>
                      _t6.close()
                      val (isUp, _desc) = tuple

                      val descriptor = if (env.redirectToDev) _desc.copy(env = "dev") else _desc

                      def callDownstream(config: GlobalConfig,
                                         apiKey: Option[ApiKey] = None,
                                         paUsr: Option[PrivateAppsUser] = None): Future[HttpResponse] = {
                        if (config.useCircuitBreakers && descriptor.clientConfig.useCircuitBreaker) {
                          val _t7 = metrics.timer("circuit_breaker_logic_duration").time()
                          env.circuitBeakersHolder
                            .get(desc.id, () => new ServiceDescriptorCircuitBreaker())
                            .call(desc, bodyAlreadyConsumed, (t) => {
                              _t7.close()
                              actuallyCallDownstream(t, apiKey, paUsr)
                            }) recoverWith {
                            case BodyAlreadyConsumedException =>
                              Errors
                                .craftResponseResult(
                                  s"Something went wrong, the downstream service does not respond quickly enough but consumed all the request body, you should try later. Thanks for your understanding",
                                  BadGateway,
                                  req,
                                  Some(descriptor),
                                  Some("errors.request.timeout")
                                )
                                .map(_.underlying)
                            case RequestTimeoutException =>
                              Errors
                                .craftResponseResult(
                                  s"Something went wrong, the downstream service does not respond quickly enough, you should try later. Thanks for your understanding",
                                  BadGateway,
                                  req,
                                  Some(descriptor),
                                  Some("errors.request.timeout")
                                )
                                .map(_.underlying)
                            case AllCircuitBreakersOpenException =>
                              Errors
                                .craftResponseResult(
                                  s"Something went wrong, the downstream service seems a little bit overwhelmed, you should try later. Thanks for your understanding",
                                  BadGateway,
                                  req,
                                  Some(descriptor),
                                  Some("errors.circuit.breaker.open")
                                )
                                .map(_.underlying)
                            case error
                                if error != null && error.getMessage != null && error.getMessage
                                  .toLowerCase()
                                  .contains("connection refused") =>
                              Errors
                                .craftResponseResult(
                                  s"Something went wrong, the connection to downstream service was refused, you should try later. Thanks for your understanding",
                                  BadGateway,
                                  req,
                                  Some(descriptor),
                                  Some("errors.connection.refused")
                                )
                                .map(_.underlying)
                            case error if error != null && error.getMessage != null =>
                              error.printStackTrace()
                              Errors
                                .craftResponseResult(
                                  s"Something went wrong, you should try later. Thanks for your understanding. ${error.getMessage}",
                                  BadGateway,
                                  req,
                                  Some(descriptor),
                                  Some("errors.proxy.error")
                                )
                                .map(_.underlying)
                            case error =>
                              Errors
                                .craftResponseResult(
                                  s"Something went wrong, you should try later. Thanks for your understanding",
                                  BadGateway,
                                  req,
                                  Some(descriptor),
                                  Some("errors.proxy.error")
                                )
                                .map(_.underlying)
                          }
                        } else {
                          val index = reqCounter.incrementAndGet() % (if (descriptor.targets.nonEmpty)
                                                                        descriptor.targets.size
                                                                      else 1)
                          // Round robin loadbalancing is happening here !!!!!
                          val target = descriptor.targets.apply(index.toInt)
                          actuallyCallDownstream(target, apiKey, paUsr)
                        }
                      }

                      def actuallyCallDownstream(target: Target,
                                                 apiKey: Option[ApiKey] = None,
                                                 paUsr: Option[PrivateAppsUser] = None): Future[HttpResponse] = {
                        val _t8         = metrics.timer("actual_call_overhead_duration").time()
                        val snowflake   = env.snowflakeGenerator.nextId().toString
                        val state       = IdGenerator.extendedToken(128)
                        val rawUri      = reqUri.substring(1)
                        val uriParts    = rawUri.split("/").toSeq
                        val uri: String = if (descriptor.matchingRoot.isDefined) uriParts.tail.mkString("/") else rawUri
                        val scheme      = if (descriptor.redirectToLocal) descriptor.localScheme else target.scheme
                        val host =
                          if (descriptor.redirectToLocal) descriptor.localHost else target.asAkkaHost._1.address()
                        val root = descriptor.root
                        val url  = s"$scheme://$host$root$uri"
                        // val queryString = req.queryString.toSeq.flatMap { case (key, values) => values.map(v => (key, v)) }

                        val fromOtoroshi = req
                          .headerValue(env.Headers.OpunGatewayRequestId)
                          .orElse(req.headerValue(env.Headers.OpunGatewayParentRequest))

                        val promise = Promise[ProxyDone]

                        val claim = OpunClaim(
                          iss = env.Headers.OpunGateway,
                          sub = paUsr
                            .filter(_ => descriptor.privateApp)
                            .map(k => s"pa:${k.email}")
                            .orElse(apiKey.map(k => s"apikey:${k.clientId}"))
                            .getOrElse("--"),
                          aud = descriptor.name,
                          exp = DateTime.now().plusSeconds(30).toDate.getTime,
                          iat = DateTime.now().toDate.getTime,
                          jti = IdGenerator.uuid
                        ).withClaim("email", paUsr.map(_.email))
                          .withClaim("name", paUsr.map(_.name).orElse(apiKey.map(_.clientName)))
                          .withClaim("picture", paUsr.flatMap(_.picture))
                          .withClaim("user_id", paUsr.flatMap(_.userId).orElse(apiKey.map(_.clientId)))
                          .withClaim("given_name", paUsr.flatMap(_.field("given_name")))
                          .withClaim("family_name", paUsr.flatMap(_.field("family_name")))
                          .withClaim("gender", paUsr.flatMap(_.field("gender")))
                          .withClaim("locale", paUsr.flatMap(_.field("locale")))
                          .withClaim("nickname", paUsr.flatMap(_.field("nickname")))
                          .withClaims(paUsr.flatMap(_.opunData).orElse(apiKey.map(_.metadata)))
                          .serialize(env)
                        logger.trace(s"Claim is : $claim")
                        val parentHeader: Option[HttpHeader] =
                          fromOtoroshi.map(fo => RawHeader(env.Headers.OpunGatewayParentRequest, fo))
                        val headersIn: Seq[HttpHeader] =
                        req.headers.filterNot(t => headersInFiltered.contains(t.name().toLowerCase)) ++ parentHeader :+
                        RawHeader(env.Headers.OpunProxiedHost, reqHost) :+
                        RawHeader(env.Headers.OpunGatewayRequestId, snowflake) :+
                        RawHeader(env.Headers.OpunGatewayState, state) :+
                        RawHeader(env.Headers.OpunGatewayClaim, claim) :+
                        Host(host) //:+
                        //RawHeader("Host", host)

                        val lazySource = Source.single(ByteString.empty).flatMapConcat { _ =>
                          bodyAlreadyConsumed.compareAndSet(false, true)
                          req.entity.dataBytes/*.map(bs => {
                            // meterIn.mark(bs.length)
                            counterIn.addAndGet(bs.length)
                            bs
                          })*/
                        }
                        val body     = lazySource
                        val overhead = System.currentTimeMillis() - start
                        val quotas: Future[RemainingQuotas] =
                          apiKey.map(_.updateQuotas()).getOrElse(FastFuture.successful(RemainingQuotas()))
                        promise.future.andThen {
                          case Success(resp) => {

                            val actualDuration: Long = System.currentTimeMillis() - start
                            val duration: Long =
                              if (descriptor.id == env.backOfficeServiceId && actualDuration > 300L) 300L
                              else actualDuration
                            // logger.trace(s"[$snowflake] Call forwarded in $duration ms. with $overhead ms overhead for (${req.version}, http://${reqHost}${reqUri} => $url, $from)")
                            metrics.timer("call_overhead").update(overhead, TimeUnit.MILLISECONDS)
                            metrics.timer("call_duration").update(duration, TimeUnit.MILLISECONDS)
                            metrics.timer("call_latency").update(resp.upstreamLatency, TimeUnit.MILLISECONDS)
                            descriptor
                              .updateMetrics(duration,
                                             overhead,
                                             0L,//counterIn.get(),
                                             0L,//counterOut.get(),
                                             resp.upstreamLatency,
                                             globalConfig)
                              .andThen {
                                case Failure(e) => logger.error("Error while updating call metrics reporting", e)
                              }
                            env.datastores.globalConfigDataStore.updateQuotas(globalConfig)
                            quotas.andThen {
                              case Success(q) => {
                                val fromLbl = req.headerValue(env.Headers.OpunVizFromLabel).getOrElse("internet")
                                val viz: OpunViz = OpunViz(
                                  to = descriptor.id,
                                  toLbl = descriptor.name,
                                  from = req.headerValue(env.Headers.OpunVizFrom).getOrElse("internet"),
                                  fromLbl = fromLbl,
                                  fromTo = s"$fromLbl###${descriptor.name}"
                                )
                                GatewayEvent(
                                  `@id` = env.snowflakeGenerator.nextId().toString,
                                  reqId = snowflake.toLong,
                                  parentReqId = fromOtoroshi.map(_.toLong),
                                  `@timestamp` = DateTime.now(),
                                  protocol = req.protocol.value,
                                  to = EventLocation(
                                    scheme = req.scheme(secure),
                                    host = reqHost,
                                    uri = reqUri
                                  ),
                                  target = EventLocation(
                                    scheme = scheme,
                                    host = host,
                                    uri = reqUri
                                  ),
                                  duration = duration,
                                  overhead = overhead,
                                  url = url,
                                  from = from,
                                  env = descriptor.env,
                                  data = DataInOut(
                                    dataIn = 0L,//counterIn.get(),
                                    dataOut = 0L,//counterOut.get()
                                  ),
                                  status = resp.status,
                                  headers = req.headers.map(h => (h.name(), h.value())).map(Header.apply),
                                  identity = apiKey
                                    .map(
                                      k =>
                                        Identity(
                                          identityType = "APIKEY",
                                          identity = k.clientId,
                                          label = k.clientName
                                      )
                                    )
                                    .orElse(
                                      paUsr.map(
                                        k =>
                                          Identity(
                                            identityType = "PRIVATEAPP",
                                            identity = k.email,
                                            label = k.name
                                        )
                                      )
                                    ),
                                  `@serviceId` = descriptor.id,
                                  `@service` = descriptor.name,
                                  descriptor = descriptor,
                                  `@product` = descriptor.metadata.getOrElse("product", "--"),
                                  remainingQuotas = q,
                                  viz = Some(viz)
                                ).toAnalytics()
                              }
                            }
                          }
                        }

                        val upstreamStart = System.currentTimeMillis()
                        val inCtype: ContentType = req
                          .header[`Content-Type`]
                          .map(_.contentType)
                          .getOrElse(ContentTypes.`application/octet-stream`)
                        val (akkaHost, akkaPort) = target.asAkkaHost
                        _t8.close()
                        _t99.close()
                        env.http
                          .singleRequest(
                            HttpRequest(
                              method = req.method,
                              uri = Uri(
                                scheme = target.scheme,
                                authority = Authority(host = akkaHost, port = akkaPort),
                                path = req.uri.toRelative.path,
                                queryString = req.uri.toRelative.rawQueryString,
                                fragment = req.uri.toRelative.fragment
                              ),
                              headers = headersIn.toList,
                              entity = HttpEntity.apply(inCtype, body),
                              protocol = HttpProtocols.`HTTP/1.1`
                            )
                          )
                          .flatMap(resp => {
                            val _t9 = metrics.timer("service_quotas_overhead_duration").time()
                            quotas.map(q => (resp, q, _t9))
                          })
                          .flatMap { tuple =>
                            val (resp, remainingQuotas, _t9) = tuple
                            _t9.close()
                            val _t10 = metrics.timer("service_called_overhead_duration").time()
                            // val responseHeader          = ByteString(s"HTTP/1.1 ${resp.headers.status}")
                            val headers = resp.headers
                            // logger.warn(s"Connection: ${resp.headersgetHeaders("Connection").map(_.last)}")
                            // if (env.notDev && !headers.get(env.Headers.OpunGatewayStateResp).contains(state)) {
                            // val validState = headers.get(env.Headers.OpunGatewayStateResp).filter(c => env.crypto.verifyString(state, c)).orElse(headers.get(env.Headers.OpunGatewayStateResp).contains(state)).getOrElse(false)
                            if (env.notDev && descriptor.enforceSecureCommunication
                                && !descriptor.isUriExcludedFromSecuredCommunication(uri)
                                && !resp.headerValue(env.Headers.OpunGatewayStateResp).contains(state)) {
                              if (resp.status
                                    .intValue() == 404 && resp.headerValue("X-CleverCloudUpgrade").contains("true")) {
                                Errors
                                  .craftResponseResult(
                                    "No service found for the specified target host, the service descriptor should be verified !",
                                    NotFound,
                                    req,
                                    Some(descriptor),
                                    Some("errors.no.service.found")
                                  )
                                  .map(_.underlying)
                              } else if (isUp) {
                                logger.error(s"\n\nError while talking with downstream service")
                                Errors
                                  .craftResponseResult(
                                    "Downstream microservice does not seems to be secured. Cancelling request !",
                                    BadGateway,
                                    req,
                                    Some(descriptor),
                                    Some("errors.service.not.secured")
                                  )
                                  .map(_.underlying)
                              } else {
                                Errors
                                  .craftResponseResult("The service seems to be down :( come back later",
                                                       Forbidden,
                                                       req,
                                                       Some(descriptor),
                                                       Some("errors.service.down"))
                                  .map(_.underlying)
                              }
                            } else {
                              val upstreamLatency = System.currentTimeMillis() - upstreamStart
                              val dailyQuotaHeader: Option[HttpHeader] = apiKey.map(
                                _ =>
                                  RawHeader(env.Headers.OpunDailyCallsRemaining,
                                            remainingQuotas.remainingCallsPerDay.toString)
                              )
                              val monthlyQuotaHeader: Option[HttpHeader] = apiKey.map(
                                _ =>
                                  RawHeader(env.Headers.OpunMonthlyCallsRemaining,
                                            remainingQuotas.remainingCallsPerMonth.toString)
                              )
                              val headersOut: Seq[HttpHeader] = headers.filterNot(
                                t => headersOutFiltered.contains(t.name().toLowerCase)
                              ) ++ dailyQuotaHeader ++ monthlyQuotaHeader :+
                              RawHeader(env.Headers.OpunGatewayRequestId, snowflake) :+
                              RawHeader(env.Headers.OpunGatewayProxyLatency, s"$overhead") :+
                              RawHeader(env.Headers.OpunGatewayUpstreamLatency, s"$upstreamLatency") :+
                              RawHeader(env.Headers.OpunTrackerId, s"${env.sign(trackingId)}::$trackingId")

                              val contentType: ContentType = resp
                                .header[`Content-Type`]
                                .map(_.contentType)
                                .getOrElse(ContentTypes.`text/plain(UTF-8)`)
                              // meterOut.mark(responseHeader.length)
                              // counterOut.addAndGet(responseHeader.length)

                              val finalStream = resp.entity.dataBytes
                                .alsoTo(Sink.onComplete(_ => {
                                  // logger.warn("stream done")
                                  promise.trySuccess(ProxyDone(resp.status.intValue(), upstreamLatency))
                                }))
                                /*.map { bs =>
                                  // logger.warn("chunk on " + reqUri)
                                  // meterOut.mark(bs.length)
                                  counterOut.addAndGet(bs.length)
                                  bs
                                }*/
                              _t10.close()
                              // stream out
                              FastFuture.successful(
                                HttpResponse(
                                  status = resp.status,
                                  headers = headersOut.toList,
                                  entity = HttpEntity(contentType, finalStream),
                                  protocol = req.protocol,
                                )
                              )
                            }
                          }
                      }

                      def passWithApiKey(config: GlobalConfig): Future[HttpResponse] = {
                        val authByJwtToken = req
                          .headerValue("Authorization")
                          .filter(_.startsWith("Bearer "))
                          .map(_.replace("Bearer ", ""))
                          .orElse(req.uri.query().get("bearer_auth"))
                        val authBasic = req
                          .headerValue("Authorization")
                          .filter(_.startsWith("Basic "))
                          .map(_.replace("Basic ", ""))
                          .flatMap(e => Try(decodeBase64(e)).toOption)
                          .orElse(
                            req.uri
                              .query()
                              .get("basic_auth")
                              .flatMap(e => Try(decodeBase64(e)).toOption)
                          )
                        val authByCustomHeaders = req
                          .headerValue(env.Headers.OpunClientId)
                          .flatMap(id => req.headerValue(env.Headers.OpunClientSecret).map(s => (id, s)))
                        if (authByCustomHeaders.isDefined) {
                          val (clientId, clientSecret) = authByCustomHeaders.get
                          env.datastores.apiKeyDataStore.findAuthorizeKeyFor(clientId, descriptor.id).flatMap {
                            case None =>
                              Errors
                                .craftResponseResult("Invalid API key",
                                                     BadGateway,
                                                     req,
                                                     Some(descriptor),
                                                     Some("errors.invalid.api.key"))
                                .map(_.underlying)
                            case Some(key) if key.isInvalid(clientSecret) => {
                              Alerts.send(
                                RevokedApiKeyUsageAlert(env.snowflakeGenerator.nextId().toString,
                                                        DateTime.now(),
                                                        env.env,
                                                        req,
                                                        key,
                                                        descriptor)
                              )
                              Errors
                                .craftResponseResult("Bad API key",
                                                     BadGateway,
                                                     req,
                                                     Some(descriptor),
                                                     Some("errors.bad.api.key"))
                                .map(_.underlying)
                            }
                            case Some(key) if key.isValid(clientSecret) =>
                              key.withingQuotas().flatMap {
                                case true => callDownstream(config, Some(key))
                                case false =>
                                  Errors
                                    .craftResponseResult("You performed too much requests",
                                                         TooManyRequests,
                                                         req,
                                                         Some(descriptor),
                                                         Some("errors.too.much.requests"))
                                    .map(_.underlying)
                              }
                          }
                        } else if (authByJwtToken.isDefined) {
                          val jwtTokenValue = authByJwtToken.get
                          Try {
                            JWT.decode(jwtTokenValue)
                          } map { jwt =>
                            Option(jwt.getClaim("clientId")).map(_.asString()) match {
                              case Some(clientId) =>
                                env.datastores.apiKeyDataStore.findAuthorizeKeyFor(clientId, descriptor.id).flatMap {
                                  case Some(apiKey) => {
                                    val algorithm = Option(jwt.getAlgorithm).map {
                                      case "HS256" => Algorithm.HMAC256(apiKey.clientSecret)
                                      case "HS512" => Algorithm.HMAC512(apiKey.clientSecret)
                                    } getOrElse Algorithm.HMAC512(apiKey.clientSecret)
                                    val verifier = JWT.require(algorithm).withIssuer(apiKey.clientName).build
                                    Try(verifier.verify(jwtTokenValue)) match {
                                      case Success(_) =>
                                        apiKey.withingQuotas().flatMap {
                                          case true => callDownstream(config, Some(apiKey))
                                          case false =>
                                            Errors
                                              .craftResponseResult("You performed too much requests",
                                                                   TooManyRequests,
                                                                   req,
                                                                   Some(descriptor),
                                                                   Some("errors.too.much.requests"))
                                              .map(_.underlying)
                                        }
                                      case Failure(e) => {
                                        Alerts.send(
                                          RevokedApiKeyUsageAlert(env.snowflakeGenerator.nextId().toString,
                                                                  DateTime.now(),
                                                                  env.env,
                                                                  req,
                                                                  apiKey,
                                                                  descriptor)
                                        )
                                        Errors
                                          .craftResponseResult("Bad API key",
                                                               BadGateway,
                                                               req,
                                                               Some(descriptor),
                                                               Some("errors.bad.api.key"))
                                          .map(_.underlying)
                                      }
                                    }
                                  }
                                  case None =>
                                    Errors
                                      .craftResponseResult("Invalid ApiKey provided",
                                                           BadRequest,
                                                           req,
                                                           Some(descriptor),
                                                           Some("errors.invalid.api.key"))
                                      .map(_.underlying)
                                }
                              case None =>
                                Errors
                                  .craftResponseResult("Invalid ApiKey provided",
                                                       BadRequest,
                                                       req,
                                                       Some(descriptor),
                                                       Some("errors.invalid.api.key"))
                                  .map(_.underlying)
                            }
                          } getOrElse Errors
                            .craftResponseResult("Invalid ApiKey provided",
                                                 BadRequest,
                                                 req,
                                                 Some(descriptor),
                                                 Some("errors.invalid.api.key"))
                            .map(_.underlying)
                        } else if (authBasic.isDefined) {
                          val auth   = authBasic.get
                          val id     = auth.split(":").headOption.map(_.trim)
                          val secret = auth.split(":").lastOption.map(_.trim)
                          (id, secret) match {
                            case (Some(apiKeyClientId), Some(apiKeySecret)) => {
                              env.datastores.apiKeyDataStore
                                .findAuthorizeKeyFor(apiKeyClientId, descriptor.id)
                                .flatMap {
                                  case None =>
                                    Errors
                                      .craftResponseResult("Invalid API key",
                                                           BadGateway,
                                                           req,
                                                           Some(descriptor),
                                                           Some("errors.invalid.api.key"))
                                      .map(_.underlying)
                                  case Some(key) if key.isInvalid(apiKeySecret) => {
                                    Alerts.send(
                                      RevokedApiKeyUsageAlert(env.snowflakeGenerator.nextId().toString,
                                                              DateTime.now(),
                                                              env.env,
                                                              req,
                                                              key,
                                                              descriptor)
                                    )
                                    Errors
                                      .craftResponseResult("Bad API key",
                                                           BadGateway,
                                                           req,
                                                           Some(descriptor),
                                                           Some("errors.bad.api.key"))
                                      .map(_.underlying)
                                  }
                                  case Some(key) if key.isValid(apiKeySecret) =>
                                    key.withingQuotas().flatMap {
                                      case true => callDownstream(config, Some(key))
                                      case false =>
                                        Errors
                                          .craftResponseResult("You performed too much requests",
                                                               TooManyRequests,
                                                               req,
                                                               Some(descriptor),
                                                               Some("errors.too.much.requests"))
                                          .map(_.underlying)
                                    }
                                }
                            }
                            case _ =>
                              Errors
                                .craftResponseResult("No ApiKey provided",
                                                     BadRequest,
                                                     req,
                                                     Some(descriptor),
                                                     Some("errors.no.api.key"))
                                .map(_.underlying)
                          }
                        } else {
                          Errors
                            .craftResponseResult("No ApiKey provided",
                                                 BadRequest,
                                                 req,
                                                 Some(descriptor),
                                                 Some("errors.no.api.key"))
                            .map(_.underlying)
                        }
                      }

                      def passWithAuth0(config: GlobalConfig): Future[HttpResponse] = {
                        isPrivateAppsSessionValid(req).flatMap {
                          case Some(paUsr) => callDownstream(config, paUsr = Some(paUsr))
                          case None => {
                            val redirectTo = env.rootScheme + env.privateAppsHost + s"/privateapps/auth0/login?redirect=http://${reqHost}${reqUri}"
                            logger.trace("should redirect to " + redirectTo)
                            FastFuture.successful(
                              Results
                                .Redirect(redirectTo)
                                .withCookies(
                                  env.removePrivateSessionCookies(
                                    ServiceDescriptorQuery(subdomain, serviceEnv, domain, "/").toHost
                                  ): _*
                                )
                                .underlying
                            )
                          }
                        }
                      }

                      val _t11 = metrics.timer("global_quotas_overhead_duration").time()
                      globalConfig.withinThrottlingQuota().map(within => (globalConfig, within)).flatMap { tuple =>
                        val (globalConfig, within) = tuple

                        // Algo is :
                        // if (app.private) {
                        //   if (uri.isPublic) {
                        //      AUTH0
                        //   } else {
                        //      APIKEY
                        //   }
                        // } else {
                        //   if (uri.isPublic) {
                        //     PASSTHROUGH without gateway auth
                        //   } else {
                        //     APIKEY
                        //   }
                        // }

                        // Very very very consuming but eh !!!
                        env.datastores.globalConfigDataStore.incrementCallsForIpAddressWithTTL(from).flatMap {
                          secCalls =>
                            env.datastores.globalConfigDataStore.quotaForIpAddress(from).map { maybeQuota =>
                              (secCalls, maybeQuota)
                            }
                        } flatMap { r =>
                          _t11.close()
                          val _t12                   = metrics.timer("service_strategy_overhead_duration").time()
                          val (secCalls, maybeQuota) = r
                          val quota                  = maybeQuota.getOrElse(globalConfig.perIpThrottlingQuota)
                          if (secCalls > (quota * 10L)) {
                            _t12.close()
                            Errors
                              .craftResponseResult("[IP] You performed too much requests",
                                                   TooManyRequests,
                                                   req,
                                                   Some(descriptor),
                                                   Some("errors.too.much.requests"))
                              .map(_.underlying)
                          } else {
                            if (env.isProd && !isSecured && desc.forceHttps) {
                              val theDomain = reqDomain
                              val protocol  = req.scheme(secure)
                              logger.info(
                                s"redirects prod service from ${protocol}://$theDomain${reqUri} to https://$theDomain${reqUri}"
                              )
                              _t12.close()
                              FastFuture.successful(Redirect(s"${env.rootScheme}$theDomain${reqUri}")).map(_.underlying)
                            } else if (!within) {
                              // TODO : count as served req here !!!
                              _t12.close()
                              Errors
                                .craftResponseResult("[GLOBAL] You performed too much requests",
                                                     TooManyRequests,
                                                     req,
                                                     Some(descriptor),
                                                     Some("errors.too.much.requests"))
                                .map(_.underlying)
                            } else if (globalConfig.ipFiltering.whitelist.nonEmpty && !globalConfig.ipFiltering.whitelist
                                         .exists(ip => RegexPool(ip).matches(remoteAddress))) {
                              _t12.close()
                              Errors
                                .craftResponseResult("Your IP address is not allowed",
                                                     Forbidden,
                                                     req,
                                                     Some(descriptor),
                                                     Some("errors.ip.address.not.allowed"))
                                .map(_.underlying) // global whitelist
                            } else if (globalConfig.ipFiltering.blacklist.nonEmpty && globalConfig.ipFiltering.blacklist
                                         .exists(ip => RegexPool(ip).matches(remoteAddress))) {
                              _t12.close()
                              Errors
                                .craftResponseResult("Your IP address is not allowed",
                                                     Forbidden,
                                                     req,
                                                     Some(descriptor),
                                                     Some("errors.ip.address.not.allowed"))
                                .map(_.underlying) // global blacklist
                            } else if (descriptor.ipFiltering.whitelist.nonEmpty && !descriptor.ipFiltering.whitelist
                                         .exists(ip => RegexPool(ip).matches(remoteAddress))) {
                              _t12.close()
                              Errors
                                .craftResponseResult("Your IP address is not allowed",
                                                     Forbidden,
                                                     req,
                                                     Some(descriptor),
                                                     Some("errors.ip.address.not.allowed"))
                                .map(_.underlying) // service whitelist
                            } else if (descriptor.ipFiltering.blacklist.nonEmpty && descriptor.ipFiltering.blacklist
                                         .exists(ip => RegexPool(ip).matches(remoteAddress))) {
                              _t12.close()
                              Errors
                                .craftResponseResult("Your IP address is not allowed",
                                                     Forbidden,
                                                     req,
                                                     Some(descriptor),
                                                     Some("errors.ip.address.not.allowed"))
                                .map(_.underlying) // service blacklist
                            } else if (globalConfig.endlessIpAddresses.nonEmpty && globalConfig.endlessIpAddresses
                                         .exists(ip => RegexPool(ip).matches(remoteAddress))) {
                              val gigas: Long = 128L * 1024L * 1024L * 1024L
                              val middleFingers = ByteString.fromString(
                                "\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95"
                              )
                              val zeros                  = ByteString.fromInts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
                              val characters: ByteString = if (!globalConfig.middleFingers) middleFingers else zeros
                              val expected: Long         = (gigas / characters.size) + 1L
                              _t12.close()
                              FastFuture.successful(
                                HttpResponse(
                                  status = StatusCodes.OK,
                                  entity = HttpEntity(ContentTypes.`application/octet-stream`,
                                                      Source.repeat(characters).limit(expected))
                                )
                              )
                            } else if (descriptor.maintenanceMode) {
                              _t12.close()
                              Errors
                                .craftResponseResult("Service in maintenance mode",
                                                     ServiceUnavailable,
                                                     req,
                                                     Some(descriptor),
                                                     Some("errors.service.in.maintenance"))
                                .map(_.underlying)
                            } else if (descriptor.buildMode) {
                              _t12.close()
                              Errors
                                .craftResponseResult("Service under construction",
                                                     ServiceUnavailable,
                                                     req,
                                                     Some(descriptor),
                                                     Some("errors.service.under.construction"))
                                .map(_.underlying)
                            } else if (isUp) {
                              if (descriptor.isPrivate) {
                                if (descriptor.isUriPublic(reqUri)) {
                                  _t12.close()
                                  passWithAuth0(globalConfig)
                                } else {
                                  isPrivateAppsSessionValid(req).flatMap {
                                    case Some(user) =>
                                      _t12.close()
                                      passWithAuth0(globalConfig)
                                    case None =>
                                      _t12.close()
                                      passWithApiKey(globalConfig)
                                  }
                                }
                              } else {
                                if (descriptor.isUriPublic(reqUri)) {
                                  _t12.close()
                                  callDownstream(globalConfig)
                                } else {
                                  _t12.close()
                                  passWithApiKey(globalConfig)
                                }
                              }
                            } else {
                              // fail fast
                              _t12.close()
                              Errors
                                .craftResponseResult("The service seems to be down :( come back later",
                                                     Forbidden,
                                                     req,
                                                     Some(descriptor),
                                                     Some("errors.service.down"))
                                .map(_.underlying)
                            }
                          }
                        }
                      }
                    }
                  }
                }
            }
          }
        }
      }
    finalResult.andThen {
      case _ =>
        val requests = env.datastores.requestsDataStore.decrementHandledRequests()
        env.datastores.globalConfigDataStore
          .singleton()
          .map(
            config =>
              env.statsd.meter(s"${env.snowflakeSeed}.concurrent-requests", requests.toDouble)(config.statsdConfig)
          )
    }
  }

  def decodeBase64(encoded: String): String = new String(OpunClaim.decoder.decode(encoded), Charsets.UTF_8)
}

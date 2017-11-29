package io.otoroshi.proxy

import java.util.concurrent.{ConcurrentHashMap, TimeUnit}
import java.util.concurrent.atomic.AtomicLong

import akka.actor.ActorSystem
import akka.http.scaladsl.{Http, HttpExt}
import akka.http.scaladsl.model.Uri.{Authority, NamedHost, Host => UriHost}
import akka.http.scaladsl.model.{HttpProtocol, _}
import akka.http.scaladsl.model.headers.{Host => HostHeader}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import com.codahale.metrics.{ConsoleReporter, MetricRegistry}
import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory
import play.api.libs.json.Json

import scala.concurrent.Future
import scala.util.Try

case class Target(scheme: String = "https",
                  host: UriHost,
                  port: Int = 80,
                  protocol: HttpProtocol = HttpProtocols.`HTTP/1.1`,
                  flow: Flow[(HttpRequest, Unit), (Try[HttpResponse], Unit), Http.HostConnectionPool])
object Target {
  def of(scheme: String, host: UriHost, port: Int)(implicit http: HttpExt, mat: ActorMaterializer): Target = {
    Target(
      scheme = scheme,
      host = host,
      port = port,
      protocol = HttpProtocols.`HTTP/1.1`,
      flow = http.cachedHostConnectionPool[Unit](host.address(), port)
    )
  }
}

object Otoroshi {

  val logger = LoggerFactory.getLogger("otoroshi")

  val conf = """
     |akka {
     |  jvm-exit-on-fatal-error = false
     |  default-dispatcher {
     |    type = Dispatcher
     |    executor = "fork-join-executor"
     |    fork-join-executor {
     |      parallelism-factor = 8.0
     |      parallelism-min = 4
     |      parallelism-max = 64
     |      task-peeking-mode = "FIFO"
     |    }
     |    throughput = 20
     |  }
     |  http {
     |    server {
     |      server-header = Otoroshi
     |      max-connections = 1024
     |      pipelining-limit = 32
     |      backlog = 100
     |      socket-options {
     |        so-receive-buffer-size = undefined
     |        so-send-buffer-size = undefined
     |        so-reuse-address = undefined
     |        so-traffic-class = undefined
     |        tcp-keep-alive = true
     |        tcp-oob-inline = undefined
     |        tcp-no-delay = undefined
     |      }
     |    }
     |    client {
     |      user-agent-header = Otoroshi
     |      socket-options {
     |        so-receive-buffer-size = undefined
     |        so-send-buffer-size = undefined
     |        so-reuse-address = undefined
     |        so-traffic-class = undefined
     |        tcp-keep-alive = true
     |        tcp-oob-inline = undefined
     |        tcp-no-delay = undefined
     |      }
     |    }
     |    host-connection-pool {
     |      max-connections = 16
     |      max-open-requests = 32
     |      pipelining-limit = 16
     |      client {
     |        user-agent-header = Otoroshi
     |        socket-options {
     |          so-receive-buffer-size = undefined
     |          so-send-buffer-size = undefined
     |          so-reuse-address = undefined
     |          so-traffic-class = undefined
     |          tcp-keep-alive = true
     |          tcp-oob-inline = undefined
     |          tcp-no-delay = undefined
     |        }
     |      }
     |    }
     |    parsing {
     |      max-uri-length             = 4k
     |      max-method-length          = 16
     |      max-response-reason-length = 64
     |      max-header-name-length     = 128
     |      max-header-value-length    = 16k
     |      max-header-count           = 128
     |      max-chunk-ext-length       = 256
     |      max-chunk-size             = 1m
     |    }
     |  }
     |}
   """.stripMargin

  implicit val system           = ActorSystem("otoroshi-system", ConfigFactory.parseString(conf))
  implicit val materializer     = ActorMaterializer()
  implicit val executionContext = system.dispatcher
  implicit val http             = Http()

  val metrics = new MetricRegistry()
  val timer   = metrics.timer("requests")
  val counter = new AtomicLong(0L)

  val reporter = ConsoleReporter
    .forRegistry(metrics)
    .convertRatesTo(TimeUnit.SECONDS)
    .convertDurationsTo(TimeUnit.MILLISECONDS)
    .build()

  val state = {
    val map = new ConcurrentHashMap[String, Seq[Target]]()
    map.put(
      "api.dev.foo.bar",
      Seq(
        Target.of("http", NamedHost("127.0.0.1"), 1030),
        Target.of("http", NamedHost("127.0.0.1"), 1031),
        Target.of("http", NamedHost("127.0.0.1"), 1032),
        Target.of("http", NamedHost("127.0.0.1"), 1033),
      )
    )
    map
  }

  def notFound(request: HttpRequest): Future[HttpResponse] = {
    request.discardEntityBytes()
    Future.successful {
      HttpResponse(404,
                   entity = HttpEntity(ContentTypes.`application/json`,
                                       Json.stringify(Json.obj("error" -> "Downstream service not found"))))
    }
  }

  def handler(request: HttpRequest): Future[HttpResponse] = {
    val ctx = timer.time()
    val fu = request.header[HostHeader] match {
      case Some(HostHeader(host, _)) =>
        Option(state.get(host.address())) match {
          case Some(targets) => {
            val index  = counter.incrementAndGet() % (if (targets.nonEmpty) targets.size else 1)
            val target = targets(index.toInt)
            val req = HttpRequest(
              method = request.method,
              uri = Uri(
                scheme = target.scheme,
                authority = Authority(host = target.host, port = target.port),
                path = request.uri.toRelative.path,
                queryString = request.uri.toRelative.rawQueryString,
                fragment = request.uri.toRelative.fragment
              ),
              headers = request.headers.filter(_ == HostHeader) :+ HostHeader(target.host.address(), target.port),
              entity = request.entity,
              protocol = target.protocol
            )
            //http.singleRequest(req).map { response =>
            Source.single((req, ())).via(target.flow).runWith(Sink.head).map { ttry =>
              val response = ttry._1.get
              HttpResponse(
                status = response.status,
                headers = response.headers.filter {
                  case akka.http.scaladsl.model.headers.`Transfer-Encoding`(_) => false
                  case _                                                       => true
                },
                entity = response.entity,
                protocol = request.protocol,
              )
            }
          }
          case None => notFound(request)
        }
      case None => notFound(request)
    }
    fu.andThen {
      case _ => ctx.close()
    }
  }

  def main(args: Array[String]) {
    val port          = Option(System.getenv("PORT")).map(_.toInt).getOrElse(8080)
    val bindingFuture = http.bindAndHandleAsync(handler, "0.0.0.0", port)
    logger.info(s"Otoroshi listening at http://0.0.0.0:$port 👹!")
    reporter.start(5, TimeUnit.SECONDS)
    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      bindingFuture
        .flatMap(_.unbind())
        .onComplete(_ => {
          reporter.stop()
          system.terminate()
          logger.info("Otoroshi server died \uD83D\uDE1F")
        })
    }))
  }
}

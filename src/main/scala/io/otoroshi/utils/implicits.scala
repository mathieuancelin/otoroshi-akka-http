package io.otoroshi.utils

import java.util.Optional

import akka.http.scaladsl.util.FastFuture

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

package object functionaljava {
  object Implicits {
    implicit class BetterOptional[A](val opt: Optional[A]) extends AnyVal {
      def asOption: Option[A] = {
        if (opt.isPresent) {
          Some(opt.get())
        } else {
          None
        }
      }
    }
  }
}

package object future {

  object Implicits {

    implicit final class EnhancedObject[A](any: A) {
      def asFuture: Future[A] = FastFuture.successful(any)
    }

    implicit final class EnhancedFuture[A](future: Future[A]) {

      def fold[U](pf: PartialFunction[Try[A], U])(implicit executor: ExecutionContext): Future[U] = {
        val promise = Promise[U]
        future.andThen {
          case underlying: Try[A] => {
            try {
              promise.trySuccess(pf(underlying))
            } catch {
              case e: Throwable => promise.tryFailure(e)
            }
          }
        }
        promise.future
      }

      def foldM[U](pf: PartialFunction[Try[A], Future[U]])(implicit executor: ExecutionContext): Future[U] = {
        val promise = Promise[U]
        future.andThen {
          case underlying: Try[A] => {
            try {
              pf(underlying).andThen {
                case Success(v) => promise.trySuccess(v)
                case Failure(e) => promise.tryFailure(e)
              }
            } catch {
              case e: Throwable => promise.tryFailure(e)
            }
          }
        }
        promise.future
      }

      def asLeft[R](implicit executor: ExecutionContext): Future[Either[A, R]] =
        future.map(a => Left[A, R](a))
    }
  }
}

package akkahttp {

  import java.net.InetSocketAddress

  import akka.http.scaladsl.model.{HttpRequest, HttpResponse, RemoteAddress}
  import akka.http.scaladsl.model.headers.{`Raw-Request-URI`, `Remote-Address`, Host}
  import functionaljava.Implicits._

  object Implicits {

    implicit class BetterHttpRequest(val request: HttpRequest) extends AnyVal {

      private def remoteAddressOfRequest(req: HttpRequest): String = {
        req.header[`Remote-Address`] match {
          case Some(`Remote-Address`(RemoteAddress.IP(ip, Some(port)))) =>
            new InetSocketAddress(ip, port).toString
          case _ => throw new IllegalStateException("`Remote-Address` header was missing")
        }
      }

      def remoteAddress = request.headerValue("X-Forwarded-Protocol").getOrElse(remoteAddressOfRequest(request))

      def uriString = request.header[`Raw-Request-URI`].map(_.uri).getOrElse(request.uri.toRelative.toString())

      def host: String = {
        val AbsoluteUri = """(?is)^(https?)://([^/]+)(/.*|$)""".r
        uriString match {
          case AbsoluteUri(_, hostPort, _) => hostPort
          case _                           => request.header[Host].map(_.host.address()).getOrElse("")
        }
      }

      def domain: String = host.split(':').head

      def headerValue(name: String): Option[String] = request.getHeader(name).asOption.map(_.value())

      def isSecured(serverSecure: Boolean) =
        request
          .headerValue("X-Forwarded-Protocol")
          .map(_ == "https")
          .orElse(Some(serverSecure))
          .getOrElse(false)

      def scheme(serverSecure: Boolean) =
        request
          .headerValue("X-Forwarded-Protocol")
          .map(_ == "https")
          .orElse(Some(serverSecure))
          .map {
            case true  => "https"
            case false => "http"
          }
          .getOrElse("http")
    }

    implicit class BetterHttpResponse(val request: HttpResponse) extends AnyVal {

      def headerValue(name: String): Option[String] = request.getHeader(name).asOption.map(_.value())

    }
  }
}

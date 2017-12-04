package play.api

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util
import java.util.BitSet
import java.util.concurrent.{ConcurrentLinkedDeque, TimeUnit}

import akka.http.scaladsl.model.HttpEntity.ChunkStreamPart
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{`Set-Cookie`, HttpCookie, RawHeader}
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.typesafe.config._
import org.slf4j.LoggerFactory
import play.api.http.{HttpStatus, Writeable}
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.Html

import scala.annotation.{implicitNotFound, tailrec}
import scala.collection.JavaConverters.{asScalaBufferConverter, _}
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

object Mode extends Enumeration {
  type Mode = Value
  val Dev, Test, Prod = Value
}

object Logger {
  def apply(name: String) = LoggerFactory.getLogger(name)
}

package object inject {

  class ApplicationLifecycle() {

    private val hooks = new ConcurrentLinkedDeque[() => Future[_]]()

    def addStopHook(hook: () => Future[_]) = hooks.push(hook)

    def stop()(implicit ec: ExecutionContext): Future[_] = {

      @tailrec
      def clearHooks(previous: Future[Any] = Future.successful[Any](())): Future[Any] = {
        val hook = hooks.poll()
        if (hook != null) clearHooks(previous.flatMap { _ =>
          hook().recover {
            case e => Logger("application").error("Error executing stop hook", e)
          }
        })
        else previous
      }

      clearHooks()
    }
  }
}

package object http {
  @implicitNotFound(
    "Cannot write an instance of ${A} to HTTP response. Try to define a Writeable[${A}]"
  )
  class Writeable[-A](val transform: A => ByteString, val contentType: ContentType) {
    def toEntity(a: A): ResponseEntity  = HttpEntity.Strict.apply(contentType, transform(a))
    def map[B](f: B => A): Writeable[B] = new Writeable(b => transform(f(b)), contentType)
  }

  object Writeable {
    implicit val StringWriteable = new Writeable[String](s => ByteString(s), ContentTypes.`text/plain(UTF-8)`)
    implicit val HtmlWriteable   = new Writeable[Html](s => ByteString(s.content), ContentTypes.`text/html(UTF-8)`)
    implicit val JsonWriteable =
      new Writeable[JsValue](s => ByteString(Json.stringify(s)), ContentTypes.`application/json`)
  }
  trait HttpStatus {
    val CONTINUE                        = 100
    val SWITCHING_PROTOCOLS             = 101
    val OK                              = 200
    val CREATED                         = 201
    val ACCEPTED                        = 202
    val NON_AUTHORITATIVE_INFORMATION   = 203
    val NO_CONTENT                      = 204
    val RESET_CONTENT                   = 205
    val PARTIAL_CONTENT                 = 206
    val MULTI_STATUS                    = 207
    val MULTIPLE_CHOICES                = 300
    val MOVED_PERMANENTLY               = 301
    val FOUND                           = 302
    val SEE_OTHER                       = 303
    val NOT_MODIFIED                    = 304
    val USE_PROXY                       = 305
    val TEMPORARY_REDIRECT              = 307
    val PERMANENT_REDIRECT              = 308
    val BAD_REQUEST                     = 400
    val UNAUTHORIZED                    = 401
    val PAYMENT_REQUIRED                = 402
    val FORBIDDEN                       = 403
    val NOT_FOUND                       = 404
    val METHOD_NOT_ALLOWED              = 405
    val NOT_ACCEPTABLE                  = 406
    val PROXY_AUTHENTICATION_REQUIRED   = 407
    val REQUEST_TIMEOUT                 = 408
    val CONFLICT                        = 409
    val GONE                            = 410
    val LENGTH_REQUIRED                 = 411
    val PRECONDITION_FAILED             = 412
    val REQUEST_ENTITY_TOO_LARGE        = 413
    val REQUEST_URI_TOO_LONG            = 414
    val UNSUPPORTED_MEDIA_TYPE          = 415
    val REQUESTED_RANGE_NOT_SATISFIABLE = 416
    val EXPECTATION_FAILED              = 417
    val UNPROCESSABLE_ENTITY            = 422
    val LOCKED                          = 423
    val FAILED_DEPENDENCY               = 424
    val UPGRADE_REQUIRED                = 426
    val TOO_MANY_REQUESTS               = 429
    val INTERNAL_SERVER_ERROR           = 500
    val NOT_IMPLEMENTED                 = 501
    val BAD_GATEWAY                     = 502
    val SERVICE_UNAVAILABLE             = 503
    val GATEWAY_TIMEOUT                 = 504
    val HTTP_VERSION_NOT_SUPPORTED      = 505
    val INSUFFICIENT_STORAGE            = 507
  }
}

object UriEncoding {
  private val segmentChars: BitSet = membershipTable(pchar)

  /** The characters allowed in a path segment; defined in RFC 3986 */
  private def pchar: Seq[Char] = {
    // RFC 3986, 2.3. Unreserved Characters
    // unreserved  = ALPHA / DIGIT / "-" / "." / "_" / "~"
    val alphaDigit = for ((min, max) <- Seq(('a', 'z'), ('A', 'Z'), ('0', '9')); c <- min to max) yield c
    val unreserved = alphaDigit ++ Seq('-', '.', '_', '~')

    // RFC 3986, 2.2. Reserved Characters
    // sub-delims  = "!" / "$" / "&" / "'" / "(" / ")"
    //             / "*" / "+" / "," / ";" / "="
    val subDelims = Seq('!', '$', '&', '\'', '(', ')', '*', '+', ',', ';', '=')

    // RFC 3986, 3.3. Path
    // pchar         = unreserved / pct-encoded / sub-delims / ":" / "@"
    unreserved ++ subDelims ++ Seq(':', '@')
  }

  /** Create a BitSet to act as a membership lookup table for the given characters. */
  private def membershipTable(chars: Seq[Char]): util.BitSet = {
    val bits = new BitSet(256)
    for (c <- chars) { bits.set(c.toInt) }
    bits
  }

  /**
   * Given a number from 0 to 16, return the ASCII character code corresponding
   * to its uppercase hexadecimal representation.
   */
  private def upperHex(x: Int): Int = {
    // Assume 0 <= x < 16
    if (x < 10) (x + '0') else (x - 10 + 'A')
  }
  def encodePathSegment(s: String, inputCharset: Charset): String = {
    encodePathSegment(s, inputCharset.name)
  }
  def encodePathSegment(s: String, inputCharset: String): String = {
    val in  = s.getBytes(inputCharset)
    val out = new ByteArrayOutputStream()
    for (b <- in) {
      val allowed = segmentChars.get(b & 0xFF)
      if (allowed) {
        out.write(b)
      } else {
        out.write('%')
        out.write(upperHex((b >> 4) & 0xF))
        out.write(upperHex(b & 0xF))
      }
    }
    out.toString("US-ASCII")
  }

}

package object mvc {

  case class Html(content: String)

  trait Action[A] {
    def apply(request: HttpRequest): Future[Result]
    def parser(entity: HttpEntity): Future[A]
  }

  case class Result(status: StatusCode, headers: List[HttpHeader], entity: ResponseEntity) {
    def underlying: HttpResponse = HttpResponse(
      status,
      headers,
      entity,
      protocol = HttpProtocols.`HTTP/1.1`
    )
    def withHeaders(_headers: (String, String)*): Result = {
      copy(headers = headers ++ _headers.map(h => RawHeader(h._1, h._2)))
    }
    def withCookies(cookies: HttpCookie*): Result = {
      copy(headers = headers ++ cookies.map(c => `Set-Cookie`(c)))
    }
    def as(ctype: ContentType) = copy(entity = entity.withContentType(ctype))
  }

  class Status(status: Int)
      extends Result(status = StatusCode.int2StatusCode(status),
                     headers = List.empty[HttpHeader],
                     entity = HttpEntity.Empty) {

    def apply[C](content: C)(implicit writeable: Writeable[C]): Result = {
      Result(
        status,
        headers,
        writeable.toEntity(content)
      )
    }

    def chunked[C](content: Source[C, _])(implicit writeable: Writeable[C]): Result = {
      copy(
        entity = HttpEntity.Chunked(writeable.contentType, content.map(c => ChunkStreamPart(writeable.transform(c))))
      )
    }

    def sendEntity(entity: ResponseEntity): Result = {
      copy(entity = entity)
    }
  }

  object Results extends HttpStatus {

    val Ok                                         = new Status(OK)
    val Created                                    = new Status(CREATED)
    val Accepted                                   = new Status(ACCEPTED)
    val NonAuthoritativeInformation                = new Status(NON_AUTHORITATIVE_INFORMATION)
    val NoContent                                  = new Status(NO_CONTENT)
    val ResetContent                               = new Status(RESET_CONTENT)
    val PartialContent                             = new Status(PARTIAL_CONTENT)
    val MultiStatus                                = new Status(MULTI_STATUS)
    def MovedPermanently(url: String): Result      = Redirect(url, MOVED_PERMANENTLY)
    def Found(url: String): Result                 = Redirect(url, FOUND)
    def SeeOther(url: String): Result              = Redirect(url, SEE_OTHER)
    val NotModified                                = new Status(NOT_MODIFIED)
    def TemporaryRedirect(url: String): Result     = Redirect(url, TEMPORARY_REDIRECT)
    def PermanentRedirect(url: String): Result     = Redirect(url, PERMANENT_REDIRECT)
    val BadRequest                                 = new Status(BAD_REQUEST)
    val Unauthorized                               = new Status(UNAUTHORIZED)
    val PaymentRequired                            = new Status(PAYMENT_REQUIRED)
    val Forbidden                                  = new Status(FORBIDDEN)
    val NotFound                                   = new Status(NOT_FOUND)
    val MethodNotAllowed                           = new Status(METHOD_NOT_ALLOWED)
    val NotAcceptable                              = new Status(NOT_ACCEPTABLE)
    val RequestTimeout                             = new Status(REQUEST_TIMEOUT)
    val Conflict                                   = new Status(CONFLICT)
    val Gone                                       = new Status(GONE)
    val PreconditionFailed                         = new Status(PRECONDITION_FAILED)
    val EntityTooLarge                             = new Status(REQUEST_ENTITY_TOO_LARGE)
    val UriTooLong                                 = new Status(REQUEST_URI_TOO_LONG)
    val UnsupportedMediaType                       = new Status(UNSUPPORTED_MEDIA_TYPE)
    val ExpectationFailed                          = new Status(EXPECTATION_FAILED)
    val UnprocessableEntity                        = new Status(UNPROCESSABLE_ENTITY)
    val Locked                                     = new Status(LOCKED)
    val FailedDependency                           = new Status(FAILED_DEPENDENCY)
    val TooManyRequests                            = new Status(TOO_MANY_REQUESTS)
    val InternalServerError                        = new Status(INTERNAL_SERVER_ERROR)
    val NotImplemented                             = new Status(NOT_IMPLEMENTED)
    val BadGateway                                 = new Status(BAD_GATEWAY)
    val ServiceUnavailable                         = new Status(SERVICE_UNAVAILABLE)
    val GatewayTimeout                             = new Status(GATEWAY_TIMEOUT)
    val HttpVersionNotSupported                    = new Status(HTTP_VERSION_NOT_SUPPORTED)
    val InsufficientStorage                        = new Status(INSUFFICIENT_STORAGE)
    def Status(code: Int)                          = new Status(code)
    def Redirect(url: String, status: Int): Result = Redirect(url, Map.empty, status)
    def Redirect(url: String, queryString: Map[String, Seq[String]] = Map.empty, status: Int = SEE_OTHER) = {
      import java.net.URLEncoder
      val fullUrl = url + Option(queryString)
        .filterNot(_.isEmpty)
        .map { params =>
          (if (url.contains("?")) "&" else "?") + params.toSeq
            .flatMap { pair =>
              pair._2.map(value => (pair._1 + "=" + URLEncoder.encode(value, "utf-8")))
            }
            .mkString("&")
        }
        .getOrElse("")
      Status(status).withHeaders("Location" -> fullUrl)
    }
  }
}

object Configuration {
  def empty                                                               = Configuration(ConfigFactory.empty())
  private[Configuration] def asScalaList[A](l: java.util.List[A]): Seq[A] = asScalaBufferConverter(l).asScala.toList
  private[api] def configError(origin: ConfigOrigin, message: String, e: Option[Throwable] = None): Exception = {
    val originLine       = Option(origin.lineNumber: java.lang.Integer).orNull
    val originUrl        = Option(origin.url)
    val originSourceName = Option(origin.filename).orNull
    new RuntimeException(s"Configuration error $message", e.orNull)
  }
}

case class Configuration(underlying: Config) {
  import Configuration.asScalaList

  val logger = Logger("application")

  def ++(other: Configuration): Configuration = {
    Configuration(other.underlying.withFallback(underlying))
  }

  private def readValue[T](path: String, v: => T): Option[T] = {
    try {
      if (underlying.hasPathOrNull(path)) Some(v) else None
    } catch {
      case NonFatal(e) => throw reportError(path, e.getMessage, Some(e))
    }
  }

  def getString(path: String, validValues: Option[Set[String]] = None): Option[String] =
    readValue(path, underlying.getString(path)).map { value =>
      validValues match {
        case Some(values) if values.contains(value) => value
        case Some(values) if values.isEmpty         => value
        case Some(values) =>
          throw reportError(path, "Incorrect value, one of " + (values.reduceLeft(_ + ", " + _)) + " was expected.")
        case None => value
      }
    }

  def getInt(path: String): Option[Int] = readValue(path, underlying.getInt(path))

  def getBoolean(path: String): Option[Boolean] = readValue(path, underlying.getBoolean(path))

  def getMilliseconds(path: String): Option[Long] = readValue(path, underlying.getDuration(path, TimeUnit.MILLISECONDS))

  def getNanoseconds(path: String): Option[Long] = readValue(path, underlying.getDuration(path, TimeUnit.NANOSECONDS))

  def getBytes(path: String): Option[Long] = readValue(path, underlying.getBytes(path))

  def getConfig(path: String): Option[Configuration] = readValue(path, underlying.getConfig(path)).map(Configuration(_))

  def getDouble(path: String): Option[Double] = readValue(path, underlying.getDouble(path))

  def getLong(path: String): Option[Long] = readValue(path, underlying.getLong(path))

  def getNumber(path: String): Option[Number] = readValue(path, underlying.getNumber(path))

  def getBooleanList(path: String): Option[java.util.List[java.lang.Boolean]] =
    readValue(path, underlying.getBooleanList(path))

  def getBooleanSeq(path: String): Option[Seq[java.lang.Boolean]] = getBooleanList(path).map(asScalaList)

  def getBytesList(path: String): Option[java.util.List[java.lang.Long]] =
    readValue(path, underlying.getBytesList(path))

  def getBytesSeq(path: String): Option[Seq[java.lang.Long]] = getBytesList(path).map(asScalaList)

  def getConfigList(path: String): Option[java.util.List[Configuration]] =
    readValue[java.util.List[_ <: Config]](path, underlying.getConfigList(path)).map { configs =>
      configs.asScala.map(Configuration(_)).asJava
    }

  def getConfigSeq(path: String): Option[Seq[Configuration]] = getConfigList(path).map(asScalaList)

  def getDoubleList(path: String): Option[java.util.List[java.lang.Double]] =
    readValue(path, underlying.getDoubleList(path))

  def getDoubleSeq(path: String): Option[Seq[java.lang.Double]] = getDoubleList(path).map(asScalaList)

  def getIntList(path: String): Option[java.util.List[java.lang.Integer]] = readValue(path, underlying.getIntList(path))

  def getIntSeq(path: String): Option[Seq[java.lang.Integer]] = getIntList(path).map(asScalaList)

  def getList(path: String): Option[ConfigList] = readValue(path, underlying.getList(path))

  def getLongList(path: String): Option[java.util.List[java.lang.Long]] = readValue(path, underlying.getLongList(path))

  def getLongSeq(path: String): Option[Seq[java.lang.Long]] = getLongList(path).map(asScalaList)

  def getMillisecondsList(path: String): Option[java.util.List[java.lang.Long]] =
    readValue(path, underlying.getDurationList(path, TimeUnit.MILLISECONDS))

  def getMillisecondsSeq(path: String): Option[Seq[java.lang.Long]] = getMillisecondsList(path).map(asScalaList)

  def getNanosecondsList(path: String): Option[java.util.List[java.lang.Long]] =
    readValue(path, underlying.getDurationList(path, TimeUnit.NANOSECONDS))

  def getNanosecondsSeq(path: String): Option[Seq[java.lang.Long]] = getNanosecondsList(path).map(asScalaList)

  def getNumberList(path: String): Option[java.util.List[java.lang.Number]] =
    readValue(path, underlying.getNumberList(path))

  def getNumberSeq(path: String): Option[Seq[java.lang.Number]] = getNumberList(path).map(asScalaList)

  def getObjectList(path: String): Option[java.util.List[_ <: ConfigObject]] =
    readValue[java.util.List[_ <: ConfigObject]](path, underlying.getObjectList(path))

  def getStringList(path: String): Option[java.util.List[java.lang.String]] =
    readValue(path, underlying.getStringList(path))

  def getStringSeq(path: String): Option[Seq[java.lang.String]] = getStringList(path).map(asScalaList)

  def getObject(path: String): Option[ConfigObject] = readValue(path, underlying.getObject(path))

  def keys: Set[String] = underlying.entrySet.asScala.map(_.getKey).toSet

  def subKeys: Set[String] = underlying.root().keySet().asScala.toSet

  def entrySet: Set[(String, ConfigValue)] = underlying.entrySet().asScala.map(e => e.getKey -> e.getValue).toSet

  def reportError(path: String, message: String, e: Option[Throwable] = None): Exception = {
    Configuration.configError(
      if (underlying.hasPath(path)) underlying.getValue(path).origin else underlying.root.origin,
      message,
      e
    )
  }

  def globalError(message: String, e: Option[Throwable] = None): Exception = {
    Configuration.configError(underlying.root.origin, message, e)
  }

  private[play] def getDeprecatedString(key: String, deprecatedKey: String): String = {
    getString(deprecatedKey).fold(underlying.getString(key)) { value =>
      logger.warn(s"$deprecatedKey is deprecated, use $key instead")
      value
    }
  }

  private[play] def getDeprecatedStringOpt(key: String, deprecatedKey: String): Option[String] = {
    getString(deprecatedKey)
      .map { value =>
        logger.warn(s"$deprecatedKey is deprecated, use $key instead")
        value
      }
      .orElse(getString(key))
      .filter(_.nonEmpty)
  }

  private[play] def getDeprecatedBoolean(key: String, deprecatedKey: String): Boolean = {
    getBoolean(deprecatedKey).fold(underlying.getBoolean(key)) { value =>
      logger.warn(s"$deprecatedKey is deprecated, use $key instead")
      value
    }
  }

  private[play] def getDeprecatedDuration(key: String, deprecatedKey: String): FiniteDuration = {
    new FiniteDuration(
      getNanoseconds(deprecatedKey).fold(underlying.getDuration(key, TimeUnit.NANOSECONDS)) { value =>
        logger.warn(s"$deprecatedKey is deprecated, use $key instead")
        value
      },
      TimeUnit.NANOSECONDS
    )
  }

  private[play] def getDeprecatedDurationOpt(key: String, deprecatedKey: String): Option[FiniteDuration] = {
    getNanoseconds(deprecatedKey)
      .map { value =>
        logger.warn(s"$deprecatedKey is deprecated, use $key instead")
        value
      }
      .orElse(getNanoseconds(key))
      .map { value =>
        new FiniteDuration(value, TimeUnit.NANOSECONDS)
      }
  }

}

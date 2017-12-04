package io.otoroshi.gateway

import akka.http.scaladsl.model.{ContentTypes, HttpRequest}
import akka.http.scaladsl.util.FastFuture
import io.otoroshi.env.Env
import io.otoroshi.models.ServiceDescriptor
import play.api.libs.json.Json
import play.api.mvc.{Result, Status}
import play.api.http.Writeable._
import io.otoroshi.utils.functionaljava.Implicits._
import io.otoroshi.utils.akkahttp.Implicits._

import scala.concurrent.{ExecutionContext, Future}

object Errors {

  val messages = Map(
    404 -> ("The page you're looking for does not exist", "notFound.gif")
  )

  def craftResponseResult(message: String,
                          status: Status,
                          req: HttpRequest,
                          maybeDescriptor: Option[ServiceDescriptor] = None,
                          maybeCauseId: Option[String])(implicit ec: ExecutionContext, env: Env): Future[Result] = {

    val errorId = env.snowflakeGenerator.nextId()

    def standardResult(): Future[Result] = {
      val accept = req.headerValue("Accept").getOrElse("text/html").split(",").toSeq
      if (accept.contains("text/html")) { // in a browser
        if (maybeCauseId.contains("errors.service.in.maintenance")) {
          FastFuture.successful(
            status
              .apply(io.otoroshi.views.Maintenance(env))
              .withHeaders(
                env.Headers.OpunGatewayError     -> "true",
                env.Headers.OpunGatewayErrorMsg  -> message,
                env.Headers.OpunGatewayStateResp -> req.headerValue(env.Headers.OpunGatewayState).getOrElse("--")
              )
          )
        } else if (maybeCauseId.contains("errors.service.under.construction")) {
          FastFuture.successful(
            status
              .apply(io.otoroshi.views.Build(env))
              .withHeaders(
                env.Headers.OpunGatewayError     -> "true",
                env.Headers.OpunGatewayErrorMsg  -> message,
                env.Headers.OpunGatewayStateResp -> req.headerValue(env.Headers.OpunGatewayState).getOrElse("--")
              )
          )
        } else {
          FastFuture.successful(
            status
              .apply(
                io.otoroshi.views.Error(message, env)
              )
              .withHeaders(
                env.Headers.OpunGatewayError     -> "true",
                env.Headers.OpunGatewayErrorMsg  -> message,
                env.Headers.OpunGatewayStateResp -> req.headerValue(env.Headers.OpunGatewayState).getOrElse("--")
              )
          )
        }
      } else {
        FastFuture.successful(
          status
            .apply(Json.obj(env.Headers.OpunGatewayError -> message))
            .withHeaders(
              env.Headers.OpunGatewayError     -> "true",
              env.Headers.OpunGatewayStateResp -> req.headerValue(env.Headers.OpunGatewayState).getOrElse("--")
            )
        )
      }
    }

    def customResult(descriptor: ServiceDescriptor): Future[Result] =
      env.datastores.errorTemplateDataStore.findById(descriptor.id).flatMap {
        case None => standardResult()
        case Some(errorTemplate) => {
          val accept = req.headerValue("Accept").getOrElse("text/html").split(",").toSeq
          if (accept.contains("text/html")) { // in a browser
            FastFuture.successful(
              status
                .apply(
                  errorTemplate
                    .renderHtml(status.status.intValue(), maybeCauseId.getOrElse("--"), message, errorId.toString)
                )
                .as(ContentTypes.`text/html(UTF-8)`)
                .withHeaders(
                  env.Headers.OpunGatewayError     -> "true",
                  env.Headers.OpunGatewayErrorMsg  -> message,
                  env.Headers.OpunGatewayStateResp -> req.headerValue(env.Headers.OpunGatewayState).getOrElse("--")
                )
            )
          } else {
            FastFuture.successful(
              status
                .apply(
                  errorTemplate
                    .renderJson(status.status.intValue(), maybeCauseId.getOrElse("--"), message, errorId.toString)
                )
                .withHeaders(
                  env.Headers.OpunGatewayError     -> "true",
                  env.Headers.OpunGatewayStateResp -> req.headerValue(env.Headers.OpunGatewayState).getOrElse("--")
                )
            )
          }
        }
      }

    maybeDescriptor match {
      case Some(desc) => customResult(desc)
      case None       => standardResult()
    }
  }
}

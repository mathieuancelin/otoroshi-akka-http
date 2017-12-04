package io.otoroshi.models

import io.otoroshi.env.Env
import scala.concurrent.{ExecutionContext, Future}

case class ServiceCanaryCampaign(canaryUsers: Long, standardUsers: Long)

trait CanaryDataStore {

  def destroyCanarySession(serviceId: String)(implicit ec: ExecutionContext, env: Env): Future[Boolean]

  def isCanary(serviceId: String, trackingId: String, traffic: Double)(implicit ec: ExecutionContext,
                                                                       env: Env): Future[Boolean]

  def canaryCampaign(serviceId: String)(implicit ec: ExecutionContext, env: Env): Future[ServiceCanaryCampaign]
}

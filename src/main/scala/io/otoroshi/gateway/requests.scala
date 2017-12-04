package io.otoroshi.gateway

import java.util.concurrent.atomic.AtomicLong

import akka.http.scaladsl.util.FastFuture

import scala.concurrent.Future

trait RequestsDataStore {
  def incrementHandledRequests(): Long
  def decrementHandledRequests(): Long
  def getHandledRequests(): Long
  def asyncGetHandledRequests(): Future[Long]
}

class InMemoryRequestsDataStore() extends RequestsDataStore {

  private lazy val handledProcessed = new AtomicLong(0L)

  override def incrementHandledRequests(): Long = handledProcessed.incrementAndGet()

  override def decrementHandledRequests(): Long = handledProcessed.decrementAndGet()

  override def getHandledRequests(): Long = handledProcessed.get()

  override def asyncGetHandledRequests(): Future[Long] = FastFuture.successful(handledProcessed.get())
}

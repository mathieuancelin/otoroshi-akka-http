package io.otoroshi.gateway

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

import akka.Done
import akka.actor.Scheduler
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.util.FastFuture
import akka.pattern.{CircuitBreaker => AkkaCircuitBreaker}
import io.otoroshi.env.Env
import io.otoroshi.events._
import io.otoroshi.models.{ServiceDescriptor, Target}
import play.api.Logger
import play.api.mvc.Result

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.control.NoStackTrace
import scala.util.{Failure, Success}

object Timeout {

  def timeout[A](message: => A, duration: FiniteDuration)(implicit ec: ExecutionContext,
                                                          scheduler: Scheduler): Future[A] = {
    val p = Promise[A]()
    scheduler.scheduleOnce(duration) {
      p.success(message)
    }
    p.future
  }
}

object Retry {

  private[this] def retryPromise[T](times: Int,
                                    delay: Long,
                                    factor: Long,
                                    promise: Promise[T],
                                    failure: Option[Throwable],
                                    f: () => Future[T])(implicit ec: ExecutionContext, scheduler: Scheduler): Unit =
    (times, failure) match {
      case (0, Some(e)) =>
        promise.tryFailure(e)
      case (0, None) =>
        promise.tryFailure(new RuntimeException("Failure, but lost track of exception :-("))
      case (i, _) =>
        f().onComplete {
          case Success(t) =>
            promise.trySuccess(t)
          case Failure(e) =>
            if (delay == 0L) {
              retryPromise[T](times - 1, 0L, factor, promise, Some(e), f)
            } else {
              val newDelay: Long = delay * factor
              Timeout.timeout(Done, delay.millis).map { _ =>
                retryPromise[T](times - 1, newDelay, factor, promise, Some(e), f)
              }
            }
        }(ec)
    }

  def retry[T](times: Int, delay: Long = 0, factor: Long = 2L)(f: () => Future[T])(implicit ec: ExecutionContext,
                                                                                   scheduler: Scheduler): Future[T] = {
    val promise = Promise[T]()
    retryPromise[T](times, delay, factor, promise, None, f)
    promise.future
  }
}

case object BodyAlreadyConsumedException extends RuntimeException("Request body already consumed") with NoStackTrace
case object RequestTimeoutException      extends RuntimeException("Global request timeout") with NoStackTrace
case object AllCircuitBreakersOpenException
    extends RuntimeException("All targets circuit breakers are open")
    with NoStackTrace

class ServiceDescriptorCircuitBreaker()(implicit ec: ExecutionContext, scheduler: Scheduler, env: Env) {

  val reqCounter = new AtomicInteger(0)
  val breakers   = new ConcurrentHashMap[String, AkkaCircuitBreaker]()

  lazy val logger = Logger("otoroshi-circuit-breaker")

  def clear(): Unit = breakers.clear()

  def chooseTarget(descriptor: ServiceDescriptor): Option[(Target, AkkaCircuitBreaker)] = {
    val targets = descriptor.targets.filterNot(t => Option(breakers.get(t.host)).map(_.isOpen).getOrElse(false))
    val index   = reqCounter.incrementAndGet() % (if (targets.nonEmpty) targets.size else 1)
    // Round robin loadbalancing is happening here !!!!!
    if (targets.isEmpty) {
      None
    } else {
      val target = targets.apply(index.toInt)
      if (!breakers.containsKey(target.host)) {
        val cb = new AkkaCircuitBreaker(
          scheduler = scheduler,
          maxFailures = descriptor.clientConfig.maxErrors,
          callTimeout = descriptor.clientConfig.callTimeout.millis,
          resetTimeout = descriptor.clientConfig.sampleInterval.millis
        )
        cb.onOpen {
          env.datastores.globalConfigDataStore.singleton().map { config =>
            env.statsd.set(s"services.${descriptor.id}.circuit-breaker", "open")(config.statsdConfig)
          }
          Audit.send(
            CircuitBreakerOpenedEvent(
              env.snowflakeGenerator.nextId().toString,
              env.env,
              target,
              descriptor
            )
          )
          Alerts.send(
            CircuitBreakerOpenedAlert(
              env.snowflakeGenerator.nextId().toString,
              env.env,
              target,
              descriptor
            )
          )
        }
        cb.onClose {
          env.datastores.globalConfigDataStore.singleton().map { config =>
            env.statsd.set(s"services.${descriptor.id}.circuit-breaker", "closed")(config.statsdConfig)
          }
          Audit.send(
            CircuitBreakerClosedEvent(
              env.snowflakeGenerator.nextId().toString,
              env.env,
              target,
              descriptor
            )
          )
          Alerts.send(
            CircuitBreakerClosedAlert(
              env.snowflakeGenerator.nextId().toString,
              env.env,
              target,
              descriptor
            )
          )
        }
        breakers.putIfAbsent(target.host, cb)
      }
      val breaker = breakers.get(target.host)
      Some((target, breaker))
    }
  }

  def call(descriptor: ServiceDescriptor, bodyAlreadyConsumed: AtomicBoolean, f: Target => Future[HttpResponse])(
      implicit env: Env
  ): Future[HttpResponse] = {
    val failure = Timeout
      .timeout(Done, descriptor.clientConfig.globalTimeout.millis)
      .flatMap(_ => FastFuture.failed(RequestTimeoutException))
    val maybeSuccess = Retry.retry(descriptor.clientConfig.retries,
                                   descriptor.clientConfig.retryInitialDelay,
                                   descriptor.clientConfig.backoffFactor) { () =>
      if (bodyAlreadyConsumed.get) {
        FastFuture.failed(BodyAlreadyConsumedException)
      } else {
        chooseTarget(descriptor) match {
          case Some((target, breaker)) =>
            breaker.withCircuitBreaker {
              logger.debug(s"Try to call target : $target")
              f(target)
            }
          case None => FastFuture.failed(AllCircuitBreakersOpenException)
        }
      }
    }
    Future.firstCompletedOf(Seq(maybeSuccess, failure))
  }
}

class CircuitBreakersHolder() {

  val circuitBreakers = new ConcurrentHashMap[String, ServiceDescriptorCircuitBreaker]()

  def get(id: String, defaultValue: () => ServiceDescriptorCircuitBreaker): ServiceDescriptorCircuitBreaker = {
    if (!circuitBreakers.containsKey(id)) {
      circuitBreakers.putIfAbsent(id, defaultValue())
    }
    circuitBreakers.get(id)
  }

  def resetAllCircuitBreakers(): Unit = circuitBreakers.clear()

  def resetCircuitBreakersFor(id: String): Unit =
    Option(circuitBreakers.get(id)).foreach(_.clear())
}

package io.otoroshi

import events.{AdminApiEvent, Audit}
import io.otoroshi.env.Env
import play.api.Logger
import play.api.mvc._
import play.api.libs.json.Json

class ApiController()(implicit env: Env) {

  implicit lazy val ec  = env.executionContext
  implicit lazy val mat = env.materializer

  lazy val logger = Logger("otoroshi-admin-api")

  def globalLiveStats() = {
    for {
      calls                     <- env.datastores.serviceDescriptorDataStore.globalCalls()
      dataIn                    <- env.datastores.serviceDescriptorDataStore.globalDataIn()
      dataOut                   <- env.datastores.serviceDescriptorDataStore.globalDataOut()
      rate                      <- env.datastores.serviceDescriptorDataStore.globalCallsPerSec()
      duration                  <- env.datastores.serviceDescriptorDataStore.globalCallsDuration()
      overhead                  <- env.datastores.serviceDescriptorDataStore.globalCallsOverhead()
      dataInRate                <- env.datastores.serviceDescriptorDataStore.dataInPerSecFor("global")
      dataOutRate               <- env.datastores.serviceDescriptorDataStore.dataOutPerSecFor("global")
      concurrentHandledRequests <- env.datastores.requestsDataStore.asyncGetHandledRequests()
    } yield {
      Results
        .Ok(
          Json.obj(
            "calls"                     -> calls,
            "dataIn"                    -> dataIn,
            "dataOut"                   -> dataOut,
            "rate"                      -> rate,
            "duration"                  -> duration,
            "overhead"                  -> overhead,
            "dataInRate"                -> dataInRate,
            "dataOutRate"               -> dataOutRate,
            "concurrentHandledRequests" -> concurrentHandledRequests
          )
        )
        .underlying
    }
  }
}

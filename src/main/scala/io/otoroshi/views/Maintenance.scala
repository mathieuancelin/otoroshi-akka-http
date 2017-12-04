package io.otoroshi.views

import io.otoroshi.env.Env
import play.api.mvc.Html

object Maintenance {
  def apply(env: Env): Html = {
    val moreStyles = """<link rel="stylesheet" media="screen" href="/__opun_assets/stylesheets/error.css">"""
    Main("Service in maintenance", env, moreStyles = moreStyles)(Html(s"""
        |<div class="jumbotron">
        |    <h2 style="color:white;"> Service in maintenance</h2>
        |    <p class="lead">
        |        The service you're trying to reach is in maintenance
        |    </p>
        |    <p class="lead">
        |        try to come back later &#128521;
        |    </p>
        |    <p><img class="logo" src="/__opun_assets/images/otoroshi-logo-color.png" style="width: 300px;" /></p>
        |</div>
      """.stripMargin))
  }
}

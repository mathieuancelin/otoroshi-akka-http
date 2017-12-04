package io.otoroshi.views

import io.otoroshi.env.Env
import play.api.mvc.Html

object Error {
  def apply(message: String, env: Env, title: String = "Otoroshi Error", error: Boolean = true): Html = {
    val moreStyles = """<link rel="stylesheet" media="screen" href="/__opun_assets/stylesheets/error.css">"""
    Main("Otoroshi error", env, moreStyles = moreStyles)(Html(s"""
      |<div class="jumbotron">
      |    ${if (error) s"""<h2><i class="glyphicon glyphicon-warning-sign"></i> $title</h2>"""
                                                                 else s"""<h2 style="color:white;">$title</h2>"""}
      |    <p class="lead">
      |        $message
      |    </p>
      |    <p><img class="logoOtoroshi" src="/__opun_assets/images/otoroshi-logo-color.png" /></p>
      |</div>
      """.stripMargin))
  }
}

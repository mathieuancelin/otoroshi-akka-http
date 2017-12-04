package io.otoroshi.views

import io.otoroshi.env.Env
import play.api.mvc.Html

object Main {
  def apply(title: String, env: Env, moreScripts: String = "", moreStyles: String = "")(content: Html): Html = {
    Html(s"""
      |<!DOCTYPE html>
      |<html lang="en">
      |    <head>
      |        <title>$title</title>
      |        <meta charset="utf-8">
      |        <meta name="viewport" content="width=device-width,initial-scale=1,shrink-to-fit=no">
      |        <meta name="theme-color" content="#000000">
      |        <link rel="shortcut icon" type="image/png" href="/__opun_assets/images/favicon.png">
      |        <link rel="stylesheet" href="/__opun_assets/stylesheets/bootstrap.min.css">
      |        <link rel="stylesheet" href="/__opun_assets/stylesheets/bootstrap-theme.min.css">
      |        <link rel="stylesheet" media="screen" href="/__opun_assets/stylesheets/opunapps.css">
      |        $moreStyles
      |    </head>
      |    <body>
      |        <div class="container">
      |            <div class="header clearfix">
      |                <h3 class="text-muted col-md-3"><span class="">おとろし</span> Otoroshi</h3>
      |            </div>
      |            $content
      |            <footer class="footer hide">
      |                <p>© 2017 Otoroshi by MAIF (version : <span class="label label-success">${env.commitId}</span> env: <span class="label label-success">${env.env}</span>)</p>
      |            </footer>
      |        </div>
      |        <script type="text/javascript" src="/__opun_assets/javascripts/jquery.js"></script>
      |        $moreScripts
      |    </body>
      |</html>
      |
    """.stripMargin)
  }
}

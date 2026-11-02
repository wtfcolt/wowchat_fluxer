package wowchat.fluxer

import java.io.IOException
import java.net.URI

import com.typesafe.scalalogging.StrictLogging
import wowchat.fluxer.Json._

import scala.util.Try

// apiBase is the public API base URL without the /v1 prefix. gatewayUrl is the ws/wss Gateway URL as published.
case class FluxerEndpoints(apiBase: String, gatewayUrl: String)

// Instance discovery (https://docs.fluxer.com/http-api/instance/): GET /.well-known/fluxer on the instance origin,
// unauthenticated. Bots use endpoints.api_public for HTTP and endpoints.gateway for the Gateway.
object FluxerDiscovery extends StrictLogging {

  private val Attempts = 3

  def wellKnownUrl(instanceUrl: String): String = {
    instanceUrl.trim.stripSuffix("/") + "/.well-known/fluxer"
  }

  def parse(body: String): Either[String, FluxerEndpoints] = {
    Json.parse(body).filter(_.isObject) match {
      case None => Left("the discovery document is not a JSON object")
      case Some(document) =>
        val endpoints = document.obj("endpoints")
        for {
          api <- endpoints.flatMap(_.str("api_public")).toRight("the discovery document has no endpoints.api_public")
          gateway <- endpoints.flatMap(_.str("gateway")).toRight("the discovery document has no endpoints.gateway")
          _ <- validateUrl(api, Set("http", "https"), "endpoints.api_public")
          _ <- validateUrl(gateway, Set("ws", "wss"), "endpoints.gateway")
        } yield FluxerEndpoints(api, gateway)
    }
  }

  def resolve(config: FluxerConfig, http: HttpTransport, sleeper: Long => Unit = Thread.sleep): FluxerEndpoints = {
    config.apiUrl.foreach(url => fail(validateUrl(url, Set("http", "https"), "fluxer.api_url")))
    config.gatewayUrl.foreach(url => fail(validateUrl(url, Set("ws", "wss"), "fluxer.gateway_url")))

    (config.apiUrl, config.gatewayUrl) match {
      case (Some(api), Some(gateway)) =>
        logger.info("Using the configured Fluxer API and Gateway URLs. Instance discovery is skipped.")
        FluxerEndpoints(api, gateway)
      case _ =>
        val instanceUrl = config.instanceUrl.map(_.trim).filter(_.nonEmpty).getOrElse(
          throw new FluxerConfigurationException("fluxer.instance_url is required unless both fluxer.api_url and fluxer.gateway_url are set.")
        )
        fail(validateUrl(instanceUrl, Set("http", "https"), "fluxer.instance_url"))
        val discovered = discover(instanceUrl, http, sleeper, config.token)
        val endpoints = FluxerEndpoints(
          config.apiUrl.getOrElse(discovered.apiBase),
          config.gatewayUrl.getOrElse(discovered.gatewayUrl)
        )
        logger.info(s"Fluxer API: ${endpoints.apiBase}, Gateway: ${endpoints.gatewayUrl}")
        endpoints
    }
  }

  private def discover(instanceUrl: String, http: HttpTransport, sleeper: Long => Unit, token: String): FluxerEndpoints = {
    val url = wellKnownUrl(instanceUrl)
    var lastError = ""
    (1 to Attempts).foreach(attempt => {
      logger.info(s"Discovering Fluxer endpoints from $url")
      val result = Try(http.execute(HttpRequest("GET", url, Seq("Accept" -> "application/json"), followRedirects = true)))
      result.fold(
        {
          case e: IOException => lastError = Redaction.redact(s"request failed: ${e.getClass.getSimpleName}: ${e.getMessage}", token)
          case e => throw e
        },
        response =>
          if (response.status == 200) {
            parse(response.body).fold(
              error => throw new FluxerConfigurationException(s"Invalid Fluxer discovery document at $url: $error"),
              endpoints => return endpoints
            )
          } else if (response.status >= 500 || response.status == 429) {
            lastError = s"HTTP ${response.status}"
          } else {
            throw new FluxerConfigurationException(s"Fluxer instance discovery at $url returned HTTP ${response.status}. Check fluxer.instance_url.")
          }
      )
      if (attempt < Attempts) {
        logger.warn(s"Fluxer instance discovery failed ($lastError). Retrying...")
        sleeper(2000L * attempt)
      }
    })
    throw new FluxerConfigurationException(s"Fluxer instance discovery at $url failed: $lastError")
  }

  private def validateUrl(url: String, schemes: Set[String], name: String): Either[String, Unit] = {
    Try(new URI(url)).toOption match {
      case Some(uri) if uri.getScheme != null && schemes.contains(uri.getScheme.toLowerCase) && uri.getHost != null => Right(())
      case _ => Left(s"$name must be an absolute ${schemes.toSeq.sorted.mkString(" or ")} URL")
    }
  }

  private def fail(result: Either[String, Unit]): Unit = {
    result.left.foreach(error => throw new FluxerConfigurationException(error))
  }
}

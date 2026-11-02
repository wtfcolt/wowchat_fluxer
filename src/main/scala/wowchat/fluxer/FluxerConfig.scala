package wowchat.fluxer

import com.typesafe.config.Config
import com.typesafe.scalalogging.StrictLogging

// Fluxer connection settings.
//  instanceUrl: origin of the Fluxer instance, which serves /.well-known/fluxer.
//  apiUrl / gatewayUrl: optional overrides of the discovered endpoints, for deployments whose discovery
//  document is unreachable or publishes internal URLs. These are a WoWChat feature, not part of the Fluxer API.
case class FluxerConfig(token: String, instanceUrl: Option[String], apiUrl: Option[String] = None, gatewayUrl: Option[String] = None) {

  // Never print the token.
  override def toString: String = {
    s"FluxerConfig(token=${Redaction.Placeholder}, instanceUrl=$instanceUrl, apiUrl=$apiUrl, gatewayUrl=$gatewayUrl)"
  }
}

class FluxerConfigurationException(message: String) extends RuntimeException(message)

object FluxerConfig extends StrictLogging {

  // Parses and validates the "fluxer" configuration section. Blank values count as unset, so an unset
  // ${?ENV_VAR} substitution and an empty string behave the same.
  def fromConfig(config: Config): FluxerConfig = {
    def opt(path: String): Option[String] = {
      if (config.hasPath(path)) Option(config.getString(path)).map(_.trim).filter(_.nonEmpty) else None
    }

    val token = opt("token").getOrElse(throw new FluxerConfigurationException(
      "fluxer.token is not set. Set the FLUXER_TOKEN environment variable or fluxer.token in the configuration file."))
    if (token.startsWith("Bot ")) {
      throw new FluxerConfigurationException("fluxer.token must be the bot token only, without the \"Bot \" prefix.")
    }
    if (!token.matches("\\d+\\..+")) {
      // Documented form: <application_id>.<secret>. Only warn, in case an instance issues tokens differently.
      logger.warn("fluxer.token does not look like a Fluxer bot token (<application_id>.<secret>).")
    }

    val result = FluxerConfig(token, opt("instance_url"), opt("api_url"), opt("gateway_url"))
    result.apiUrl.foreach(url => check(FluxerDiscovery.validateUrl(url, Set("http", "https"), "fluxer.api_url")))
    result.gatewayUrl.foreach(url => check(FluxerDiscovery.validateUrl(url, Set("ws", "wss"), "fluxer.gateway_url")))
    result.instanceUrl match {
      case Some(url) => check(FluxerDiscovery.validateUrl(url, Set("http", "https"), "fluxer.instance_url"))
      case None if result.apiUrl.isEmpty || result.gatewayUrl.isEmpty =>
        throw new FluxerConfigurationException("fluxer.instance_url is not set. Set the FLUXER_INSTANCE_URL environment " +
          "variable or fluxer.instance_url to the address of your Fluxer instance, for example https://fluxer.example.com.")
      case None =>
    }
    result
  }

  private def check(result: Either[String, Unit]): Unit = {
    result.left.foreach(error => throw new FluxerConfigurationException(error))
  }
}

object Redaction {

  val Placeholder = "[REDACTED]"

  // Removes the token (and the token part of a bot token) from text that may end up in a log or exception.
  def redact(text: String, token: String): String = {
    if (text == null || token == null || token.isEmpty) {
      text
    } else {
      val secret = token.indexOf('.') match {
        case -1 => None
        case i => Some(token.substring(i + 1)).filter(_.length >= 8)
      }
      val withoutToken = text.replace(token, Placeholder)
      secret.fold(withoutToken)(withoutToken.replace(_, Placeholder))
    }
  }
}

package wowchat.fluxer

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

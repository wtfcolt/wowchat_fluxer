package wowchat.fluxer

import java.nio.charset.StandardCharsets

// Gateway opcodes (https://docs.fluxer.com/gateway/opcodes-and-close-codes/).
object Opcodes {
  val Dispatch = 0
  val Heartbeat = 1
  val Identify = 2
  val PresenceUpdate = 3
  val Resume = 6
  val Reconnect = 7
  val InvalidSession = 9
  val Hello = 10
  val HeartbeatAck = 11
  val GatewayError = 12
}

// Builders for the client commands this bridge sends (https://docs.fluxer.com/gateway/commands/).
object FluxerPayloads {

  // Client messages over 16,384 bytes close the connection with 4002 (Gateway overview, Framing).
  val MaxPayloadBytes = 16384

  // The events "What a bot should send" recommends ignoring. An unknown name would close the connection with 4002,
  // so only these documented names are used.
  val IgnoredEvents = Seq("TYPING_START", "MESSAGE_REACTION_ADD", "MESSAGE_REACTION_REMOVE")

  val CustomStatusMaxLength = 128

  def identify(token: String): String = {
    val payload = Json.obj()
    payload.put("op", Opcodes.Identify)
    val d = payload.putObject("d")
    // The raw token, with no "Bot " prefix.
    d.put("token", token)
    val properties = d.putObject("properties")
    properties.put("os", Option(System.getProperty("os.name")).filter(_.nonEmpty).getOrElse("unknown"))
    properties.put("browser", "wowchat")
    properties.put("device", "wowchat")
    val presence = d.putObject("presence")
    presence.put("status", "online")
    presence.put("afk", false)
    val ignored = d.putArray("ignored_events")
    IgnoredEvents.foreach(ignored.add)
    Json.write(payload)
  }

  def resume(token: String, sessionId: String, seq: Long): String = {
    val payload = Json.obj()
    payload.put("op", Opcodes.Resume)
    val d = payload.putObject("d")
    d.put("token", token)
    d.put("session_id", sessionId)
    d.put("seq", seq)
    Json.write(payload)
  }

  def heartbeat(seq: Option[Long]): String = {
    val payload = Json.obj()
    payload.put("op", Opcodes.Heartbeat)
    seq.fold(payload.putNull("d"))(s => payload.put("d", s))
    Json.write(payload)
  }

  // Replaces the session presence with "online" and the given custom status text (cleared when empty).
  def presence(text: Option[String]): String = {
    val payload = Json.obj()
    payload.put("op", Opcodes.PresenceUpdate)
    val d = payload.putObject("d")
    d.put("status", "online")
    d.put("afk", false)
    d.put("mobile", false)
    customStatusText(text) match {
      case Some(value) => d.putObject("custom_status").put("text", value)
      case None => d.putNull("custom_status")
    }
    Json.write(payload)
  }

  // Custom status text is 1-128 characters. Counted in code points so an emoji is never cut in half.
  def customStatusText(text: Option[String]): Option[String] = {
    text.map(_.trim).filter(_.nonEmpty).map(value => {
      if (value.codePointCount(0, value.length) <= CustomStatusMaxLength) {
        value
      } else {
        value.substring(0, value.offsetByCodePoints(0, CustomStatusMaxLength))
      }
    })
  }

  def byteLength(payload: String): Int = payload.getBytes(StandardCharsets.UTF_8).length
}

sealed trait CloseHandling
object CloseHandling {
  // Reconnect, resuming the session if it is still resumable.
  case object Reconnect extends CloseHandling
  // Configuration or protocol problem that a reconnect cannot fix.
  case class Fatal(reason: String) extends CloseHandling
}

// Close codes (https://docs.fluxer.com/gateway/opcodes-and-close-codes/).
object CloseCodes {

  val AuthenticationFailed = 4004
  val InvalidSequence = 4007
  val InvalidShard = 4010
  val ShardingRequired = 4011
  val InvalidApiVersion = 4012

  // Used when the socket closed without a close frame.
  val Abnormal = 1006

  def describe(code: Int): String = code match {
    case 4000 => "Unknown error"
    case 4001 => "Unknown opcode"
    case 4002 => "Decode error"
    case 4003 => "Not authenticated"
    case 4004 => "Authentication failed"
    case 4005 => "Already authenticated"
    case 4007 => "Invalid sequence"
    case 4008 => "Rate limited"
    case 4009 => "Session timeout"
    case 4010 => "Invalid shard"
    case 4011 => "Sharding required"
    case 4012 => "Invalid API version"
    case 1000 => "Normal closure"
    case 1001 => "Going away"
    case 1006 => "Connection lost"
    case _ => "Unknown close code"
  }

  // 4004 during a Resume is handled by the session (the token may still be fine), so it is not listed here.
  def handling(code: Int): CloseHandling = code match {
    case AuthenticationFailed =>
      CloseHandling.Fatal("Fluxer rejected the bot token (Gateway close 4004). Check fluxer.token.")
    case InvalidShard =>
      CloseHandling.Fatal("Fluxer rejected the shard settings (Gateway close 4010).")
    case ShardingRequired =>
      CloseHandling.Fatal("The bot is in more than 2,500 guilds and Fluxer requires sharding (Gateway close 4011), which WoWChat does not support.")
    case InvalidApiVersion =>
      CloseHandling.Fatal("Fluxer rejected the Gateway protocol version (Gateway close 4012).")
    case _ =>
      CloseHandling.Reconnect
  }
}

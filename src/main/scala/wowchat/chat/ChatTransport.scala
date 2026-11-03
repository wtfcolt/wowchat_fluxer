package wowchat.chat

// Callbacks from a chat transport into the platform-neutral chat logic.
trait ChatTransportListener {

  // The transport is connected and these text channels are available for routing.
  def onChannelsAvailable(channels: Seq[ChatChannel]): Unit

  // A message was received in a text channel. The transport has already dropped messages that should
  // never be relayed (for example the bot's own messages and non-text channels).
  def onMessage(message: IncomingChatMessage): Unit

  def onDisconnected(): Unit

  // The transport cannot continue (for example the service rejected the credentials).
  def onFatalError(reason: String): Unit
}

// A connection to a specific chat service.
trait ChatTransport {

  // Human readable service name, used in log messages.
  def name: String

  // Maximum length of a single outgoing message.
  def maxMessageLength: Int

  def start(listener: ChatTransportListener): Unit
  def shutdown(): Unit

  // Sends one message chunk that is already within maxMessageLength.
  def postMessage(channel: ChatChannel, message: String): Unit

  def setStatus(message: String): Unit

  // Service-specific formatting of outgoing WoW messages.
  def resolveMentions(channel: ChatChannel, message: String, onError: String => Unit): String
  def resolveEmojis(message: String): String
  def escapeFormatting(message: String): String
  def formatLink(label: String, url: String): String
}

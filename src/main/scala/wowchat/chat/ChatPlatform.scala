package wowchat.chat

// Platform-neutral representations of chat entities. IDs are kept as strings.
case class ChatChannel(id: String, name: String)
case class ChatUser(id: String, displayName: String)
case class IncomingChatMessage(channel: ChatChannel, author: ChatUser, content: String, attachmentUrls: Seq[String])

// Everything the WoW side of the application needs from the chat side.
trait ChatPlatform {

  // Whether a WoW chat message of this type (and custom channel name, if any) is routed anywhere.
  // The game packet handlers use this to skip parsing messages nobody is listening to.
  def shouldForwardWowChat(tp: Byte, wowChannel: Option[String]): Boolean

  def sendMessageFromWow(from: Option[String], message: String, wowType: Byte, wowChannel: Option[String]): Unit
  def sendGuildNotification(eventKey: String, message: String): Unit
  def sendAchievementNotification(name: String, achievementId: Int): Unit

  // Sends a plain message to a chat channel, splitting it as needed.
  def sendMessage(channel: ChatChannel, message: String): Unit

  def changeGuildStatus(message: String): Unit
  def changeRealmStatus(message: String): Unit

  def shutdown(): Unit
}

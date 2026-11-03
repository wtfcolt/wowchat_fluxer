package wowchat

import wowchat.chat._
import wowchat.common.{Global, WowChatConfig}
import wowchat.game.GameCommandHandler

import scala.collection.mutable

object TestSupport {

  // WowChatConfig fixes the expansion (and therefore the chat event ids) the first time it is read,
  // so every test in the JVM shares one configuration: test.conf, Vanilla.
  lazy val baseConfig: WowChatConfig = WowChatConfig("test.conf")

  def resetGlobals(): Unit = {
    Global.config = baseConfig
    Global.game = None
    Global.chat = null
  }
}

// Records everything sent through it. Formatting hooks mimic simple markdown so their order can be checked.
class FakeTransport extends ChatTransport {

  val posted = mutable.ArrayBuffer.empty[(ChatChannel, String)]
  val statuses = mutable.ArrayBuffer.empty[String]
  var listener: ChatTransportListener = _
  var shutdownCalled = false
  var maxLength = 2000

  // tag -> mention, or None to report an error for that tag
  var mentions: Map[String, Option[String]] = Map.empty

  override def name: String = "Fake"
  override def maxMessageLength: Int = maxLength
  override def start(listener: ChatTransportListener): Unit = this.listener = listener
  override def shutdown(): Unit = shutdownCalled = true
  override def postMessage(channel: ChatChannel, message: String): Unit = posted += channel -> message
  override def setStatus(message: String): Unit = statuses += message

  override def resolveMentions(channel: ChatChannel, message: String, onError: String => Unit): String = {
    mentions.foldLeft(message) {
      case (result, (tag, Some(mention))) => result.replace(s"@$tag", mention)
      case (result, (tag, None)) =>
        if (result.contains(s"@$tag")) onError(s"bad tag @$tag")
        result
    }
  }

  override def resolveEmojis(message: String): String = message.replace(":pog:", "<:pog:1>")
  override def escapeFormatting(message: String): String = message.replace("*", "\\*").replace("_", "\\_")
  override def formatLink(label: String, url: String): String = s"[$label]($url)"

  def postedTo(channelId: String): Seq[String] = posted.filter(_._1.id == channelId).map(_._2)
}

class FakeGame extends GameCommandHandler {

  val sent = mutable.ArrayBuffer.empty[(Byte, String, Option[String])]
  var whoResult: Option[String] = None
  var gmotdResult: Option[String] = None
  val whoCalls = mutable.ArrayBuffer.empty[Option[String]]

  override def sendMessageToWow(tp: Byte, message: String, target: Option[String]): Unit = sent += ((tp, message, target))
  override def sendNotification(message: String): Unit = ()
  override def handleWho(arguments: Option[String]): Option[String] = {
    whoCalls += arguments
    whoResult
  }
  override def handleGmotd(): Option[String] = gmotdResult
}

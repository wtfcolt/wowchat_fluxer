package wowchat.common

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import io.netty.channel.EventLoopGroup
import wowchat.chat.ChatPlatform
import wowchat.game.GameCommandHandler

object Global {

  var group: EventLoopGroup = _
  var config: WowChatConfig = _

  var chat: ChatPlatform = _
  var game: Option[GameCommandHandler] = None

  def getTime: String = {
    LocalDateTime.now.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
  }
}

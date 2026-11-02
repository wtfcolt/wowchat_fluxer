package wowchat.chat

import com.typesafe.scalalogging.StrictLogging
import com.vdurmont.emoji.EmojiParser
import wowchat.commands.CommandHandler
import wowchat.common._
import wowchat.game.GamePackets

import scala.collection.mutable

object ChatBridge {

  private[chat] def splitUpByLength(message: String, maxLength: Int): Seq[String] = {
    val retArr = mutable.ArrayBuffer.empty[String]

    var tmp = message
    while (tmp.length > maxLength) {
      val subStr = tmp.substring(0, maxLength)
      val spaceIndex = subStr.lastIndexOf(' ')
      tmp = if (spaceIndex == -1) {
        retArr += subStr
        tmp.substring(maxLength)
      } else {
        retArr += subStr.substring(0, spaceIndex)
        tmp.substring(spaceIndex + 1)
      }
    }

    if (tmp.nonEmpty) {
      retArr += tmp
    }

    retArr
  }

  private[chat] def splitUpMessageToWow(format: String, name: String, message: String): Seq[String] = {
    val maxTmpLen = 255 - format
      .replace("%time", Global.getTime)
      .replace("%user", name)
      .replace("%message", "")
      .length

    splitUpByLength(message, maxTmpLen)
      .map(message => {
        val formatted = format
          .replace("%time", Global.getTime)
          .replace("%user", name)
          .replace("%message", message)

        // If the final formatted message is a dot command, it should be disabled. Add a space in front.
        if (formatted.startsWith(".")) {
          s" $formatted"
        } else {
          formatted
        }
      })
  }

  // Routing tables built from the configuration and the channels the transport reported as available.
  private[chat] case class Routing(
    chatToWow: Map[String, Seq[WowChannelConfig]],
    wowToChat: Map[(Byte, Option[String]), Seq[(ChatChannel, FluxerChannelConfig)]],
    guildEventsToChat: Map[String, Seq[ChatChannel]]
  )

  private[chat] val emptyRouting = Routing(Map.empty, Map.empty, Map.empty)
}

// Platform-neutral chat logic: routing between WoW and chat channels, message formatting, filters,
// dot commands and the ?who/?gmotd commands. Service-specific behavior is delegated to the transport.
class ChatBridge(transport: ChatTransport, connectionCallback: CommonConnectionCallback) extends ChatPlatform
  with ChatTransportListener with GamePackets with StrictLogging {

  import ChatBridge._

  private val messageResolver = MessageResolver(transport.formatLink)

  // Replaced as a whole whenever channels become available, so readers on the WoW thread always see a
  // complete snapshot.
  @volatile private var routing: Routing = emptyRouting
  @volatile private var lastStatus: Option[String] = None
  private var firstConnect = true

  def start(): Unit = {
    transport.start(this)
  }

  override def shutdown(): Unit = {
    transport.shutdown()
  }

  override def shouldForwardWowChat(tp: Byte, wowChannel: Option[String]): Boolean = {
    routing.wowToChat.contains((tp, wowChannel.map(_.toLowerCase)))
  }

  override def sendMessage(channel: ChatChannel, message: String): Unit = {
    splitUpByLength(message, transport.maxMessageLength).foreach(transport.postMessage(channel, _))
  }

  override def changeGuildStatus(message: String): Unit = {
    changeStatus(message)
  }

  override def changeRealmStatus(message: String): Unit = {
    changeStatus(message)
  }

  private def changeStatus(message: String): Unit = {
    lastStatus = Some(message)
    transport.setStatus(message)
  }

  override def sendMessageFromWow(from: Option[String], message: String, wowType: Byte, wowChannel: Option[String]): Unit = {
    routing.wowToChat.get((wowType, wowChannel.map(_.toLowerCase))).foreach(chatChannels => {
      val parsedLinks = transport.resolveEmojis(messageResolver.stripColorCoding(messageResolver.resolveLinks(message)))

      chatChannels.foreach {
        case (channel, channelConfig) =>
          val errors = mutable.ArrayBuffer.empty[String]
          val parsedResolvedTags = transport.escapeFormatting(
            from.map(_ => {
              transport.resolveMentions(channel, parsedLinks, errors += _)
            })
              .getOrElse(parsedLinks)
          )

          val formatted = channelConfig
            .format
            .replace("%time", Global.getTime)
            .replace("%user", from.getOrElse(""))
            .replace("%message", parsedResolvedTags)
            .replace("%target", wowChannel.getOrElse(""))

          val filter = shouldFilter(channelConfig.filters, formatted)
          logger.info(s"${if (filter) "FILTERED " else ""}WoW->${transport.name}(${channel.name}) $formatted")
          if (!filter) {
            sendMessage(channel, formatted)
          }
          if (Global.config.chat.enableTagFailedNotifications) {
            errors.foreach(error => {
              Global.game.foreach(_.sendMessageToWow(ChatEvents.CHAT_MSG_WHISPER, error, from))
              sendMessage(channel, error)
            })
          }
      }
    })
  }

  override def sendGuildNotification(eventKey: String, message: String): Unit = {
    val currentRouting = routing
    currentRouting.guildEventsToChat
      .getOrElse(eventKey, currentRouting.wowToChat.getOrElse(
          (ChatEvents.CHAT_MSG_GUILD, None), Seq.empty
        ).map(_._1).distinct
      )
      .foreach(channel => {
        logger.info(s"WoW->${transport.name}(${channel.name}) $message")
        sendMessage(channel, message)
      })
  }

  override def sendAchievementNotification(name: String, achievementId: Int): Unit = {
    val notificationConfig = Global.config.guildConfig.notificationConfigs("achievement")
    if (!notificationConfig.enabled) {
      return
    }

    routing.wowToChat.get((ChatEvents.CHAT_MSG_GUILD, None))
      .foreach(_.foreach {
        case (channel, _) =>
          val formatted = notificationConfig
            .format
            .replace("%time", Global.getTime)
            .replace("%user", name)
            .replace("%achievement", messageResolver.resolveAchievementId(achievementId))

          logger.info(s"WoW->${transport.name}(${channel.name}) $formatted")
          sendMessage(channel, formatted)
      })
  }

  override def onChannelsAvailable(channels: Seq[ChatChannel]): Unit = {
    lastStatus.foreach(transport.setStatus)

    routing = buildRouting(channels)

    if (routing.chatToWow.nonEmpty || routing.wowToChat.nonEmpty) {
      if (firstConnect) {
        connectionCallback.connected
        firstConnect = false
      } else {
        connectionCallback.reconnected
      }
    } else {
      logger.error(s"No ${transport.name} channels configured!")
    }
  }

  override def onDisconnected(): Unit = {
    connectionCallback.disconnected
  }

  private[chat] def buildRouting(channels: Seq[ChatChannel]): Routing = {
    val chatToWow = mutable.LinkedHashMap.empty[String, mutable.ArrayBuffer[WowChannelConfig]]
    val wowToChat = mutable.LinkedHashMap.empty[(Byte, Option[String]), mutable.ArrayBuffer[(ChatChannel, FluxerChannelConfig)]]
    val guildEventsToChat = mutable.LinkedHashMap.empty[String, mutable.ArrayBuffer[ChatChannel]]

    // config channel name (or id) -> channel config
    val configChannels = Global.config.channels.map(channelConfig => {
      channelConfig.fluxer.channel.toLowerCase -> channelConfig
    })

    // build directional maps
    channels.foreach(channel => {
      configChannels
        .filter {
          case (name, _) =>
            name.equalsIgnoreCase(channel.name) ||
            name == channel.id
        }
        .foreach {
          case (name, channelConfig) =>
            if (channelConfig.chatDirection == ChatDirection.both ||
              channelConfig.chatDirection == ChatDirection.fluxer_to_wow) {
              chatToWow.getOrElseUpdate(name, mutable.ArrayBuffer.empty) += channelConfig.wow
            }

            if (channelConfig.chatDirection == ChatDirection.both ||
              channelConfig.chatDirection == ChatDirection.wow_to_fluxer) {
              wowToChat.getOrElseUpdate(
                (channelConfig.wow.tp, channelConfig.wow.channel.map(_.toLowerCase)), mutable.ArrayBuffer.empty
              ) += channel -> channelConfig.fluxer
            }
        }
    })

    // build guild notification maps
    val guildEventChannels = Global.config.guildConfig.notificationConfigs
      .filter {
        case (_, notificationConfig) =>
          notificationConfig.enabled
      }
      .flatMap {
        case (key, notificationConfig) =>
          notificationConfig.channel.map(key -> _)
      }

    channels.foreach(channel => {
      guildEventChannels
        .filter {
          case (_, name) =>
            name.equalsIgnoreCase(channel.name) ||
            name == channel.id
        }
        .foreach {
          case (notificationKey, _) =>
            guildEventsToChat.getOrElseUpdate(notificationKey, mutable.ArrayBuffer.empty) += channel
        }
    })

    // the previous implementation used sets, so drop exact duplicates
    Routing(
      chatToWow.map { case (k, v) => k -> v.distinct.toList }.toMap,
      wowToChat.map { case (k, v) => k -> v.distinct.toList }.toMap,
      guildEventsToChat.map { case (k, v) => k -> v.distinct.toList }.toMap
    )
  }

  override def onMessage(incoming: IncomingChatMessage): Unit = {
    val channel = incoming.channel
    val channelName = channel.name.toLowerCase
    val effectiveName = sanitizeName(incoming.author.displayName)
    val message = (sanitizeMessage(incoming.content) +: incoming.attachmentUrls)
      .filter(_.nonEmpty)
      .mkString(" ")
    val enableCommandsChannels = Global.config.chat.enableCommandsChannels
    logger.debug(s"RECV ${transport.name.toUpperCase} MESSAGE: [${channel.name}] [$effectiveName]: $message")
    if (message.isEmpty) {
      logger.error(s"Received a message in channel ${channel.name} but the content was empty.")
    }

    if ((enableCommandsChannels.nonEmpty && !enableCommandsChannels.contains(channelName)) || !CommandHandler(channel, message)) {
      // send to all configured wow channels
      val currentRouting = routing
      currentRouting.chatToWow
        .get(channelName)
        .orElse(currentRouting.chatToWow.get(channel.id))
        .foreach(_.foreach(channelConfig => {
          val finalMessages = if (shouldSendDirectly(message)) {
            Seq(message)
          } else {
            splitUpMessageToWow(channelConfig.format, effectiveName, message)
          }

          finalMessages.foreach(finalMessage => {
            val filter = shouldFilter(channelConfig.filters, finalMessage)
            logger.info(s"${if (filter) "FILTERED " else ""}${transport.name}->WoW(${
              channelConfig.channel.getOrElse(ChatEvents.valueOf(channelConfig.tp))
            }) $finalMessage")
            if (!filter) {
              Global.game.fold(logger.error("Cannot send message! Not connected to WoW!"))(handler => {
                handler.sendMessageToWow(channelConfig.tp, finalMessage, channelConfig.channel)
              })
            }
          })
        }))
    }
  }

  def shouldSendDirectly(message: String): Boolean = {
    val chatConf = Global.config.chat
    val trimmed = message.drop(1).toLowerCase

    message.startsWith(".") &&
    chatConf.enableDotCommands &&
      (
        chatConf.dotCommandsWhitelist.isEmpty ||
        chatConf.dotCommandsWhitelist.contains(trimmed) ||
        // Theoretically it would be better to construct a prefix tree for this.
        !chatConf.dotCommandsWhitelist.forall(item => {
          if (item.endsWith("*")) {
            !trimmed.startsWith(item.dropRight(1).toLowerCase)
          } else {
            true
          }
        })
      )
  }

  def shouldFilter(filtersConfig: Option[FiltersConfig], message: String): Boolean = {
    filtersConfig
      .fold(Global.config.filters)(Some(_))
      .exists(filters => filters.enabled && filters.patterns.exists(message.matches))
  }

  def sanitizeName(name: String): String = {
    name.replace("|", "||")
  }

  def sanitizeMessage(message: String): String = {
    EmojiParser.parseToAliases(message, EmojiParser.FitzpatrickAction.REMOVE)
  }
}

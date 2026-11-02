package wowchat.fluxer

import java.util.concurrent.{Executors, ScheduledExecutorService, ScheduledFuture, TimeUnit}

import com.fasterxml.jackson.databind.JsonNode
import com.typesafe.scalalogging.StrictLogging
import wowchat.chat._
import wowchat.fluxer.Json._

import scala.util.Try

object FluxerTransport {

  // Bots can always send at least 4,000 characters (https://docs.fluxer.com/http-api/messages/, Create message).
  val MaxMessageLength = 4000

  // Message types relayed to WoW: DEFAULT and REPLY.
  val RelayedMessageTypes = Set(0, 19)

  //  initialLoadTimeoutMs: how long to wait for the Guild Create of every guild listed in READY before routing
  //    with what has arrived.
  //  presenceIntervalMs: Presence Update allows 5 per 20 seconds per connection; stay below that.
  //  memberPageSize: List guild members returns at most 1,000 members per request.
  case class Timings(initialLoadTimeoutMs: Long = 30000L, presenceIntervalMs: Long = 5000L, memberPageSize: Int = 1000)

  // Converts a MESSAGE_CREATE payload into a message for the bridge, or None if it must not be relayed.
  def toIncoming(data: JsonNode, snapshot: FluxerSnapshot): Option[IncomingChatMessage] = {
    for {
      channelId <- data.id("channel_id")
      if data.id("guild_id").isDefined
      if data.int("channel_type").contains(FluxerCache.GuildTextChannelType)
      if data.int("type").exists(RelayedMessageTypes.contains)
      author <- data.obj("author")
      authorId <- author.id("id")
      if !snapshot.selfUserId.contains(authorId)
      channel <- snapshot.channel(channelId)
      guild <- snapshot.guildForChannel(channelId)
    } yield {
      val username = author.str("username").getOrElse(authorId)
      val nick = data.obj("member").flatMap(_.str("nick"))
      val displayName = CachedMember(authorId, username, None, author.str("global_name"), nick).displayName

      // Mentioned users come with the message; prefer their guild nickname from the cache.
      val mentioned = data.arr("mentions").flatMap(user => {
        user.id("id").map(id => {
          id -> guild.members.get(id).map(_.displayName).getOrElse(
            CachedMember(id, user.str("username").getOrElse(id), None, user.str("global_name"), None).displayName)
        })
      }).toMap
      val users = guild.members.map { case (id, member) => id -> member.displayName } ++ mentioned
      val channels = snapshot.guilds.values.flatMap(_.channels.values).map(c => c.id -> c.name).toMap
      val content = FluxerContentRenderer.render(data.str("content").getOrElse(""), users, guild.roles, channels)

      // Expired attachments have a null url.
      val attachments = data.arr("attachments").flatMap(_.str("url"))

      IncomingChatMessage(channel, ChatUser(authorId, displayName), content, attachments)
    }
  }
}

// Fluxer implementation of ChatTransport: discovery, the HTTP API for sending and member lists, and the Gateway
// for receiving events. Outgoing messages use HTTP only, so they keep flowing while the Gateway reconnects.
class FluxerTransport(config: FluxerConfig,
                      http: HttpTransport = new UrlConnectionHttpTransport,
                      gatewayFactory: (String, String, GatewayListener) => GatewayConnection =
                        (url, token, listener) => new FluxerGateway(url, token, listener),
                      discoverySleeper: Long => Unit = Thread.sleep,
                      timings: FluxerTransport.Timings = FluxerTransport.Timings()) extends ChatTransport with StrictLogging {

  import FluxerTransport._

  private val cache = new FluxerCache
  private val scheduler: ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor(FluxerRestClient.daemonThreads("fluxer-transport"))

  @volatile private var listener: ChatTransportListener = _
  @volatile private var rest: Option[FluxerRestClient] = None
  @volatile private var gateway: Option[GatewayConnection] = None

  // Guarded by `this`.
  private var pendingGuilds = Set.empty[String]
  private var routingAnnounced = false
  private var initialLoadTimeout: Option[ScheduledFuture[_]] = None
  private var latestStatus: Option[String] = None
  private var lastPresenceSentAt = 0L
  private var presenceFlush: Option[ScheduledFuture[_]] = None
  private val memberSyncInProgress = scala.collection.mutable.Set.empty[String]

  override def name: String = "Fluxer"

  override def maxMessageLength: Int = MaxMessageLength

  private[fluxer] def snapshot: FluxerSnapshot = cache.snapshot

  override def start(listener: ChatTransportListener): Unit = {
    this.listener = listener
    if (config.token == null || config.token.trim.isEmpty) {
      listener.onFatalError("fluxer.token is not set.")
      return
    }

    val endpoints = Try(FluxerDiscovery.resolve(config, http, discoverySleeper)).fold(e => {
      listener.onFatalError(Redaction.redact(e.getMessage, config.token))
      return
    }, identity)

    rest = Some(new FluxerRestClient(http, endpoints.apiBase, config.token))
    val connection = gatewayFactory(endpoints.gatewayUrl, config.token, new GatewayListener {
      override def onDispatch(event: String, data: JsonNode): Unit = handleDispatch(event, data)
      override def onDisconnected(code: Int, reason: String): Unit = FluxerTransport.this.listener.onDisconnected()
      override def onFatal(reason: String): Unit = FluxerTransport.this.listener.onFatalError(reason)
    })
    gateway = Some(connection)
    connection.start()
  }

  override def shutdown(): Unit = {
    gateway.foreach(_.shutdown())
    scheduler.shutdownNow()
    rest.foreach(_.shutdown(5000))
  }

  override def postMessage(channel: ChatChannel, message: String): Unit = {
    rest match {
      case Some(client) => client.createMessageAsync(channel.id, message)
      case None => logger.error(s"Cannot send a message to Fluxer channel ${channel.name}: not started.")
    }
  }

  override def setStatus(message: String): Unit = synchronized {
    latestStatus = Some(message)
    schedulePresenceFlush()
  }

  override def resolveMentions(channel: ChatChannel, message: String, onError: String => Unit): String = {
    FluxerFormatting.resolveMentions(cache.snapshot, channel, message, onError)
  }

  override def resolveEmojis(message: String): String = FluxerFormatting.resolveEmojis(cache.snapshot, message)

  override def escapeFormatting(message: String): String = FluxerFormatting.escape(message)

  override def formatLink(label: String, url: String): String = FluxerFormatting.formatLink(label, url)

  private[fluxer] def handleDispatch(event: String, data: JsonNode): Unit = {
    event match {
      case "READY" =>
        val guildIds = data.arr("guilds").flatMap(_.id("id"))
        cache.reset(gateway.flatMap(_.selfUserId).orElse(data.obj("user").flatMap(_.id("id"))), guildIds)
        logger.info(s"Fluxer session started. Waiting for ${guildIds.size} guild(s).")
        synchronized {
          pendingGuilds = guildIds.toSet
          routingAnnounced = false
          initialLoadTimeout.foreach(_.cancel(false))
          initialLoadTimeout = None
          if (pendingGuilds.isEmpty) {
            announceRouting()
          } else {
            initialLoadTimeout = Some(scheduler.schedule(new Runnable {
              override def run(): Unit = FluxerTransport.this.synchronized {
                if (!routingAnnounced) {
                  logger.warn(s"${pendingGuilds.size} Fluxer guild(s) did not arrive in time. Continuing without them.")
                  announceRouting()
                }
              }
            }, timings.initialLoadTimeoutMs, TimeUnit.MILLISECONDS))
          }
          // A new session starts with the account's saved presence; publish ours again.
          lastPresenceSentAt = 0L
          schedulePresenceFlush()
        }

      case "RESUMED" =>
        logger.info("Fluxer session resumed.")
        synchronized {
          schedulePresenceFlush()
        }

      case "MESSAGE_CREATE" =>
        cache.applyDispatch(event, data)
        FluxerTransport.toIncoming(data, cache.snapshot).foreach(message => listener.onMessage(message))

      case _ =>
        val update = cache.applyDispatch(event, data)
        update.guildBecameAvailable.foreach(syncMembers)
        synchronized {
          event match {
            case "GUILD_CREATE" | "GUILD_DELETE" =>
              data.id("id").foreach(id => pendingGuilds -= id)
              if (!routingAnnounced && pendingGuilds.isEmpty) {
                announceRouting()
              } else if (routingAnnounced && update.textChannelsChanged) {
                announceRouting()
              }
            case _ =>
              if (routingAnnounced && update.textChannelsChanged) {
                announceRouting()
              }
          }
        }
    }
  }

  // Must hold the lock.
  private def announceRouting(): Unit = {
    initialLoadTimeout.foreach(_.cancel(false))
    initialLoadTimeout = None
    routingAnnounced = true
    val channels = cache.snapshot.textChannels
    logger.info(s"Fluxer text channels available: ${channels.map(_.name).mkString(", ")}")
    listener.onChannelsAvailable(channels)
  }

  // Must hold the lock.
  private def schedulePresenceFlush(): Unit = {
    if (latestStatus.isDefined && presenceFlush.forall(_.isDone)) {
      val delay = math.max(0L, lastPresenceSentAt + timings.presenceIntervalMs - System.currentTimeMillis)
      presenceFlush = Some(scheduler.schedule(new Runnable {
        override def run(): Unit = FluxerTransport.this.synchronized {
          lastPresenceSentAt = System.currentTimeMillis
          gateway.foreach(_.sendWhenReady(FluxerPayloads.presence(latestStatus), "Presence Update"))
        }
      }, delay, TimeUnit.MILLISECONDS))
    }
  }

  // Loads a guild's member list over HTTP (it needs no permission), so @tags from WoW can be resolved.
  private def syncMembers(guildId: String): Unit = {
    val start = synchronized {
      memberSyncInProgress.add(guildId)
    }
    if (start) {
      rest.foreach(_.runMemberTask(() => {
        try {
          var after: Option[String] = None
          var total = 0
          var done = false
          while (!done) {
            rest.get.listGuildMembers(guildId, after, timings.memberPageSize) match {
              case Right(members) =>
                total += cache.upsertMembers(guildId, members)
                after = members.lastOption.flatMap(_.obj("user")).flatMap(_.id("id"))
                done = members.size < timings.memberPageSize || after.isEmpty
              case Left(failure) =>
                logger.warn(s"Could not load the member list of Fluxer guild $guildId: $failure. @tags from WoW may not resolve.")
                done = true
            }
          }
          logger.info(s"Loaded $total member(s) of Fluxer guild $guildId.")
        } finally {
          synchronized {
            memberSyncInProgress.remove(guildId)
          }
        }
      }))
    }
  }
}

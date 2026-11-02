package wowchat.chat

import org.junit.Assert._
import org.junit.{Before, Test}
import wowchat.common.{CommonConnectionCallback, Global}
import wowchat.game.{GamePackets, GameResources}
import wowchat.{FakeGame, FakeTransport, TestSupport}

class ChatBridgeTest extends GamePackets {

  private val guildChat = ChatChannel("100", "guild-chat")
  private val officers = ChatChannel("200", "officers")
  private val tradeChat = ChatChannel("300", "trade-chat")
  private val announcements = ChatChannel("400", "announcements")
  private val guildEvents = ChatChannel("500", "guild-events")
  private val unrelated = ChatChannel("999", "off-topic")
  private val allChannels = Seq(guildChat, officers, tradeChat, announcements, guildEvents, unrelated)

  private var transport: FakeTransport = _
  private var game: FakeGame = _
  private var bridge: ChatBridge = _
  private var connectedCalls = 0
  private var reconnectedCalls = 0

  @Before
  def setUp(): Unit = {
    TestSupport.resetGlobals()
    transport = new FakeTransport
    game = new FakeGame
    connectedCalls = 0
    reconnectedCalls = 0
    bridge = new ChatBridge(transport, new CommonConnectionCallback {
      override def connected: Unit = connectedCalls += 1
      override def reconnected: Unit = reconnectedCalls += 1
    })
    Global.chat = bridge
    Global.game = Some(game)
    bridge.start()
  }

  private def connect(): Unit = bridge.onChannelsAvailable(allChannels)

  private def incoming(channel: ChatChannel, content: String, author: String = "Bob", attachments: Seq[String] = Seq.empty): Unit = {
    bridge.onMessage(IncomingChatMessage(channel, ChatUser("u1", author), content, attachments))
  }

  // ---- routing ----

  @Test
  def nothingIsForwardedBeforeChannelsAreAvailable(): Unit = {
    assertFalse(bridge.shouldForwardWowChat(ChatEvents.CHAT_MSG_GUILD, None))
  }

  @Test
  def routesByChannelNameCaseInsensitivelyAndById(): Unit = {
    connect()
    assertTrue(bridge.shouldForwardWowChat(ChatEvents.CHAT_MSG_GUILD, None))    // "Guild-Chat" in config
    assertTrue(bridge.shouldForwardWowChat(ChatEvents.CHAT_MSG_OFFICER, None))  // "200" in config
    assertTrue(bridge.shouldForwardWowChat(ChatEvents.CHAT_MSG_CHANNEL, Some("Trade - City")))
    assertTrue(bridge.shouldForwardWowChat(ChatEvents.CHAT_MSG_CHANNEL, Some("TRADE - CITY")))
    assertFalse(bridge.shouldForwardWowChat(ChatEvents.CHAT_MSG_CHANNEL, Some("General")))
    // fluxer_to_wow only, so WoW Say is not forwarded
    assertFalse(bridge.shouldForwardWowChat(ChatEvents.CHAT_MSG_SAY, None))
  }

  @Test
  def unresolvedChannelsAreNotRouted(): Unit = {
    bridge.onChannelsAvailable(Seq(guildChat))
    assertTrue(bridge.shouldForwardWowChat(ChatEvents.CHAT_MSG_GUILD, None))
    assertFalse(bridge.shouldForwardWowChat(ChatEvents.CHAT_MSG_OFFICER, None))
  }

  @Test
  def connectedThenReconnectedCallbacks(): Unit = {
    connect()
    connect()
    assertEquals(1, connectedCalls)
    assertEquals(1, reconnectedCalls)
  }

  @Test
  def noCallbackWhenNoConfiguredChannelExists(): Unit = {
    bridge.onChannelsAvailable(Seq(unrelated))
    assertEquals(0, connectedCalls)
    assertFalse(bridge.shouldForwardWowChat(ChatEvents.CHAT_MSG_GUILD, None))
  }

  // ---- WoW -> chat ----

  @Test
  def formatsAndEscapesPlayerMessages(): Unit = {
    connect()
    bridge.sendMessageFromWow(Some("Bob"), "hello *world*", ChatEvents.CHAT_MSG_GUILD, None)
    assertEquals(Seq("[Bob]: hello \\*world\\*"), transport.postedTo("100"))
  }

  @Test
  def usesPerChannelFormat(): Unit = {
    connect()
    bridge.sendMessageFromWow(Some("Bob"), "hi", ChatEvents.CHAT_MSG_OFFICER, None)
    assertEquals(Seq("<Bob> hi"), transport.postedTo("200"))
    assertTrue(transport.postedTo("100").isEmpty)
  }

  @Test
  def customChannelTargetUsesWowChannelName(): Unit = {
    connect()
    bridge.sendMessageFromWow(Some("Bob"), "WTB sword", ChatEvents.CHAT_MSG_CHANNEL, Some("Trade - City"))
    assertEquals(Seq("[Trade - City] [Bob]: WTB sword"), transport.postedTo("300"))
  }

  @Test
  def resolvesItemLinksAndStripsColors(): Unit = {
    connect()
    bridge.sendMessageFromWow(Some("Bob"), "look |cff1eff00|Hitem:1234:0:0:0|h[Sword]|h|r and |cffff0000red|r", ChatEvents.CHAT_MSG_GUILD, None)
    assertEquals(Seq("[Bob]: look [[Sword]](http://classicdb.ch?item=1234) and red"), transport.postedTo("100"))
  }

  @Test
  def resolvesEmojis(): Unit = {
    connect()
    bridge.sendMessageFromWow(Some("Bob"), "gg :pog:", ChatEvents.CHAT_MSG_GUILD, None)
    assertEquals(Seq("[Bob]: gg <:pog:1>"), transport.postedTo("100"))
  }

  @Test
  def resolvesMentionsBeforeEscaping(): Unit = {
    connect()
    transport.mentions = Map("alice" -> Some("<@a_1>"))
    bridge.sendMessageFromWow(Some("Bob"), "hi @alice", ChatEvents.CHAT_MSG_GUILD, None)
    // escaping runs after mention resolution, as in the original implementation
    assertEquals(Seq("[Bob]: hi <@a\\_1>"), transport.postedTo("100"))
  }

  @Test
  def systemMessagesDoNotResolveMentions(): Unit = {
    connect()
    transport.mentions = Map("alice" -> Some("<@1>"))
    bridge.sendMessageFromWow(None, "hi @alice", ChatEvents.CHAT_MSG_GUILD, None)
    assertEquals(Seq("[]: hi @alice"), transport.postedTo("100"))
  }

  @Test
  def failedTagsAreReportedInChatAndWhisperedInGame(): Unit = {
    connect()
    transport.mentions = Map("nobody" -> None)
    bridge.sendMessageFromWow(Some("Bob"), "hi @nobody", ChatEvents.CHAT_MSG_GUILD, None)
    assertEquals(Seq("[Bob]: hi @nobody", "bad tag @nobody"), transport.postedTo("100"))
    assertEquals(Seq((ChatEvents.CHAT_MSG_WHISPER, "bad tag @nobody", Some("Bob"))), game.sent)
  }

  @Test
  def failedTagNotificationsCanBeDisabled(): Unit = {
    Global.config = Global.config.copy(chat = Global.config.chat.copy(enableTagFailedNotifications = false))
    connect()
    transport.mentions = Map("nobody" -> None)
    bridge.sendMessageFromWow(Some("Bob"), "hi @nobody", ChatEvents.CHAT_MSG_GUILD, None)
    assertEquals(Seq("[Bob]: hi @nobody"), transport.postedTo("100"))
    assertTrue(game.sent.isEmpty)
  }

  @Test
  def globalFiltersApplyWithoutChannelFilters(): Unit = {
    connect()
    bridge.sendMessageFromWow(Some("Bob"), "a badword here", ChatEvents.CHAT_MSG_GUILD, None)
    assertTrue(transport.posted.isEmpty)
  }

  @Test
  def channelFiltersOverrideGlobalFilters(): Unit = {
    connect()
    bridge.sendMessageFromWow(Some("Bob"), "WTS sword", ChatEvents.CHAT_MSG_CHANNEL, Some("Trade - City"))
    assertTrue(transport.posted.isEmpty)
    bridge.sendMessageFromWow(Some("Bob"), "badword", ChatEvents.CHAT_MSG_CHANNEL, Some("Trade - City"))
    assertEquals(Seq("[Trade - City] [Bob]: badword"), transport.postedTo("300"))
  }

  @Test
  def splitsLongMessagesAtTransportLimit(): Unit = {
    connect()
    transport.maxLength = 12
    bridge.sendMessageFromWow(Some("Bob"), "aaa bbb ccc", ChatEvents.CHAT_MSG_GUILD, None)
    assertEquals(Seq("[Bob]: aaa", "bbb ccc"), transport.postedTo("100"))
  }

  @Test
  def unroutedWowMessagesAreDropped(): Unit = {
    connect()
    bridge.sendMessageFromWow(Some("Bob"), "hi", ChatEvents.CHAT_MSG_YELL, None)
    assertTrue(transport.posted.isEmpty)
  }

  // ---- notifications ----

  @Test
  def guildNotificationUsesConfiguredChannel(): Unit = {
    connect()
    bridge.sendGuildNotification("promoted", "promo")
    assertEquals(Seq("promo"), transport.postedTo("500"))
    assertTrue(transport.postedTo("100").isEmpty)
  }

  @Test
  def guildNotificationFallsBackToGuildChat(): Unit = {
    connect()
    bridge.sendGuildNotification("joined", "welcome")
    assertEquals(Seq("welcome"), transport.postedTo("100"))
  }

  @Test
  def achievementNotificationGoesToGuildChat(): Unit = {
    connect()
    bridge.sendAchievementNotification("Bob", 6)
    val name = GameResources.ACHIEVEMENT.getOrElse(6, 6)
    assertEquals(Seq(s"Bob has earned the achievement [[$name]](http://classicdb.ch?achievement=6)!"), transport.postedTo("100"))
  }

  @Test
  def achievementNotificationCanBeDisabled(): Unit = {
    val guildConfig = Global.config.guildConfig
    val disabled = guildConfig.notificationConfigs("achievement").copy(enabled = false)
    Global.config = Global.config.copy(guildConfig = guildConfig.copy(
      notificationConfigs = guildConfig.notificationConfigs + ("achievement" -> disabled)))
    connect()
    bridge.sendAchievementNotification("Bob", 6)
    assertTrue(transport.posted.isEmpty)
  }

  // ---- status ----

  @Test
  def statusIsSetAndRestoredWhenChannelsBecomeAvailableAgain(): Unit = {
    bridge.changeRealmStatus("Test Realm")
    bridge.changeGuildStatus("3 online")
    assertEquals(Seq("Test Realm", "3 online"), transport.statuses)
    connect()
    assertEquals(Seq("Test Realm", "3 online", "3 online"), transport.statuses)
  }

  // ---- chat -> WoW ----

  @Test
  def incomingMessagesAreFormattedForWow(): Unit = {
    connect()
    incoming(guildChat, "hello", author = "Al|ice", attachments = Seq("https://cdn.invalid/a.png"))
    assertEquals(Seq((ChatEvents.CHAT_MSG_GUILD, "[Al||ice]: hello https://cdn.invalid/a.png", None)), game.sent)
  }

  @Test
  def incomingMessagesRouteByChannelId(): Unit = {
    connect()
    incoming(officers, "hi")
    assertEquals(Seq((ChatEvents.CHAT_MSG_OFFICER, "(Bob) hi", None)), game.sent)
  }

  @Test
  def incomingUnicodeEmojiBecomeAliases(): Unit = {
    connect()
    incoming(guildChat, "hi 😀")
    assertEquals(Seq((ChatEvents.CHAT_MSG_GUILD, "[Bob]: hi :grinning:", None)), game.sent)
  }

  @Test
  def incomingMessagesForWowOnlyRelaysAreIgnored(): Unit = {
    connect()
    incoming(tradeChat, "hello")
    assertTrue(game.sent.isEmpty)
  }

  @Test
  def incomingMessagesAreSplitForWow(): Unit = {
    connect()
    val words = Seq.fill(60)("word").mkString(" ") // 299 characters
    incoming(guildChat, words)
    assertEquals(2, game.sent.size)
    game.sent.foreach { case (_, message, _) => assertTrue(message.length <= 255) }
    assertTrue(game.sent.forall(_._2.startsWith("[Bob]: ")))
  }

  @Test
  def incomingMessagesRespectGlobalFilters(): Unit = {
    connect()
    incoming(guildChat, "badword")
    assertTrue(game.sent.isEmpty)
  }

  @Test
  def whitelistedDotCommandsAreSentDirectly(): Unit = {
    connect()
    incoming(guildChat, ".server info")
    incoming(guildChat, ".lookup item sword")
    assertEquals(Seq(".server info", ".lookup item sword"), game.sent.map(_._2))
  }

  @Test
  def nonWhitelistedDotCommandsAreDisarmed(): Unit = {
    connect()
    incoming(announcements, ".kick Bob")
    // the format is just %message, so the leading dot must be disarmed with a space
    assertEquals(Seq((ChatEvents.CHAT_MSG_SAY, " .kick Bob", None)), game.sent)
  }

  @Test
  def dotCommandsCanBeDisabled(): Unit = {
    Global.config = Global.config.copy(chat = Global.config.chat.copy(enableDotCommands = false))
    connect()
    incoming(announcements, ".server info")
    assertEquals(Seq(" .server info"), game.sent.map(_._2))
  }

  @Test
  def emptyWhitelistAllowsAllDotCommands(): Unit = {
    Global.config = Global.config.copy(chat = Global.config.chat.copy(dotCommandsWhitelist = Set.empty))
    connect()
    incoming(announcements, ".kick Bob")
    assertEquals(Seq(".kick Bob"), game.sent.map(_._2))
  }

  @Test
  def commandsAreAnsweredInChatAndNotRelayed(): Unit = {
    connect()
    Global.game = None
    incoming(guildChat, "?who")
    assertEquals(Seq("Bot is not online."), transport.postedTo("100"))
  }

  @Test
  def commandsAreRelayedOutsideCommandChannels(): Unit = {
    Global.config = Global.config.copy(chat = Global.config.chat.copy(enableCommandsChannels = Set("bot-commands")))
    connect()
    incoming(guildChat, "?who")
    assertTrue(transport.posted.isEmpty)
    assertEquals(Seq("[Bob]: ?who"), game.sent.map(_._2))
  }

  @Test
  def incomingMessagesWithoutGameConnectionAreDropped(): Unit = {
    connect()
    Global.game = None
    incoming(guildChat, "hello")
    assertTrue(transport.posted.isEmpty)
  }

  // ---- lifecycle ----

  @Test
  def shutdownIsDelegatedToTransport(): Unit = {
    bridge.shutdown()
    assertTrue(transport.shutdownCalled)
  }

  // ---- helpers ----

  @Test
  def splitUpByLength(): Unit = {
    assertEquals(Seq("short"), ChatBridge.splitUpByLength("short", 10))
    assertEquals(Seq("aaa", "bbb"), ChatBridge.splitUpByLength("aaa bbb", 5))
    assertEquals(Seq("abcde", "fgh"), ChatBridge.splitUpByLength("abcdefgh", 5))
    assertEquals(Seq.empty, ChatBridge.splitUpByLength("", 5))
  }
}

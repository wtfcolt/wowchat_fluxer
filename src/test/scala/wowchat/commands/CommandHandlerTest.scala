package wowchat.commands

import org.junit.Assert._
import org.junit.{Before, Test}
import wowchat.chat.{ChatBridge, ChatChannel}
import wowchat.common.{CommonConnectionCallback, Global}
import wowchat.game.{GameResources, GuildInfo, GuildMember}
import wowchat.{FakeGame, FakeTransport, TestSupport}

import scala.collection.mutable

class CommandHandlerTest {

  private val channel = ChatChannel("100", "guild-chat")
  private var transport: FakeTransport = _
  private var game: FakeGame = _

  @Before
  def setUp(): Unit = {
    TestSupport.resetGlobals()
    transport = new FakeTransport
    game = new FakeGame
    Global.chat = new ChatBridge(transport, new CommonConnectionCallback {})
    Global.game = Some(game)
  }

  @Test
  def nonCommandsAreNotHandled(): Unit = {
    assertFalse(CommandHandler(channel, "hello"))
    assertFalse(CommandHandler(channel, "?unknown"))
    assertTrue(transport.posted.isEmpty)
  }

  @Test
  def commandsReportWhenBotIsOffline(): Unit = {
    Global.game = None
    assertTrue(CommandHandler(channel, "?who"))
    assertTrue(CommandHandler(channel, "?gmotd"))
    assertEquals(Seq("Bot is not online.", "Bot is not online."), transport.postedTo("100"))
  }

  @Test
  def onlineListsGuildies(): Unit = {
    game.whoResult = Some("Currently 2 guildies online")
    assertTrue(CommandHandler(channel, "?online"))
    assertEquals(Seq(None), game.whoCalls)
    assertEquals(Seq("Currently 2 guildies online"), transport.postedTo("100"))
  }

  @Test
  def whoWithNameQueriesGameAndRemembersChannel(): Unit = {
    assertTrue(CommandHandler(channel, "?who Bob"))
    assertEquals(Seq(Some("Bob")), game.whoCalls)
    assertEquals(WhoRequest(channel, "Bob"), CommandHandler.whoRequest)
    assertTrue(transport.posted.isEmpty)
  }

  @Test
  def overlongWhoArgumentIsIgnored(): Unit = {
    assertTrue(CommandHandler(channel, "?WHO abcdefghijklmnopq"))
    assertEquals(Seq(None), game.whoCalls)
  }

  @Test
  def gmotd(): Unit = {
    game.gmotdResult = Some("Raid at 8")
    assertTrue(CommandHandler(channel, "?gmotd"))
    assertEquals(Seq("Raid at 8"), transport.postedTo("100"))
  }

  @Test
  def gmotdWithoutMotdSendsNothing(): Unit = {
    assertTrue(CommandHandler(channel, "?gmotd"))
    assertTrue(transport.posted.isEmpty)
  }

  @Test
  def whoResponseForOnlinePlayer(): Unit = {
    val response = WhoResponse("Bob", "Guild", 60, "Mage", "Human", Some("Male"), "Stormwind")
    assertEquals(Seq("Bob <Guild> is a level 60 Male Human Mage currently in Stormwind."),
      CommandHandler.handleWhoResponse(Some(response), null, mutable.Map.empty, _ => true).toSeq)
  }

  @Test
  def whoResponseForOfflineGuildMember(): Unit = {
    // 1.5 days = 1 day 12 hours 0 minutes; class 8 = Mage; zone 1
    val roster = mutable.Map(1L -> GuildMember("Bob", isOnline = false, 8, 60, 1, 1.5f))
    val zone = GameResources.AREA.getOrElse(1, "Unknown Zone")
    assertEquals(Seq(s"Bob <Guild> is a level 60 Mage currently offline. Last seen 1 day, 12 hours, 0 minutes ago in $zone."),
      CommandHandler.handleWhoResponse(None, GuildInfo("Guild", Map.empty), roster, _.name == "Bob").toSeq)
  }
}

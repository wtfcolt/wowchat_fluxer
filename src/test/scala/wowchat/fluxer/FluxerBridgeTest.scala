package wowchat.fluxer

import org.junit.Assert._
import org.junit.{After, Before, Test}
import wowchat.chat.ChatBridge
import wowchat.common.{CommonConnectionCallback, Global}
import wowchat.fluxer.FluxerTestSupport._
import wowchat.fluxer.Json._
import wowchat.game.GamePackets
import wowchat.{FakeGame, TestSupport}

// ChatBridge running on the real FluxerTransport (with fake HTTP and Gateway), using test.conf routing.
class FluxerBridgeTest extends GamePackets {

  private var http: FakeHttp = _
  private var gateway: FakeGatewayConnection = _
  private var game: FakeGame = _
  private var transport: FluxerTransport = _
  private var bridge: ChatBridge = _
  private var connected = 0
  private var errors = 0

  @Before
  def setUp(): Unit = {
    TestSupport.resetGlobals()
    http = new FakeHttp(request => {
      if (request.url.endsWith("/.well-known/fluxer")) {
        response(200, """{"endpoints":{"api_public":"https://api.example.invalid","gateway":"wss://gateway.example.invalid"}}""")
      } else if (request.url.contains("/members")) {
        response(200, """[{"user":{"id":"1500000000000000001","username":"alice","global_name":"Alice"}}]""")
      } else {
        response(200, """{"id":"1"}""")
      }
    })
    game = new FakeGame
    Global.game = Some(game)
    transport = new FluxerTransport(FluxerConfig(Token, Some("https://chat.example.invalid")), http,
      (url, token, l) => { gateway = new FakeGatewayConnection(url, token, l); gateway }, _ => ())
    bridge = new ChatBridge(transport, new CommonConnectionCallback {
      override def connected: Unit = FluxerBridgeTest.this.connected += 1
      override def error: Unit = errors += 1
    })
    Global.chat = bridge
    bridge.start()

    gateway.dispatch("READY", """{"session_id":"s","user":{"id":"900"},"guilds":[{"id":"10","unavailable":true}]}""")
    gateway.dispatch("GUILD_CREATE",
      """{"id":"10","roles":[{"id":"10","name":"@everyone"}],"emojis":[{"id":"50","name":"pog_champ","animated":false}],"members":[],
        |"channels":[{"id":"100","type":0,"name":"guild-chat"},{"id":"200","type":0,"name":"officers"}]}""".stripMargin)
  }

  @After
  def tearDown(): Unit = bridge.shutdown()

  private def eventually(condition: => Boolean): Unit = {
    val deadline = System.currentTimeMillis + 5000
    while (!condition && System.currentTimeMillis < deadline) Thread.sleep(10)
    assertTrue(condition)
  }

  private def postedContents(channelId: String): Seq[String] = http.synchronized {
    http.requests.filter(r => r.method == "POST" && r.url.endsWith(s"/channels/$channelId/messages")).flatMap(_.body).map(json(_).str("content").get)
  }

  @Test
  def connectsOnceChannelsAreKnown(): Unit = {
    assertEquals(1, connected)
    assertTrue(bridge.shouldForwardWowChat(ChatEvents.CHAT_MSG_GUILD, None))
  }

  @Test
  def wowMessageIsPostedWithFluxerFormatting(): Unit = {
    eventually(transport.snapshot.guilds("10").members.nonEmpty)
    bridge.sendMessageFromWow(Some("Bob"), "hi @alice *look* :pog_champ:", ChatEvents.CHAT_MSG_GUILD, None)
    eventually(postedContents("100").nonEmpty)
    // mention and custom emoji are left intact while the markdown is escaped
    assertEquals(Seq("[Bob]: hi <@1500000000000000001> \\*look\\* <:pog_champ:50>"), postedContents("100"))
  }

  @Test
  def fluxerMessageReachesWow(): Unit = {
    gateway.dispatch("MESSAGE_CREATE",
      """{"id":"5","channel_id":"200","guild_id":"10","channel_type":0,"type":0,"content":"inv me <:pog_champ:50>",
        |"author":{"id":"7","username":"gwen"},"member":{"nick":"Gwen"},"mentions":[],"attachments":[]}""".stripMargin)
    // test.conf routes the channel with id 200 to officer chat
    assertEquals(Seq((ChatEvents.CHAT_MSG_OFFICER, "(Gwen) inv me :pog_champ:", None)), game.sent)
  }

  @Test
  def gatewayFatalErrorReachesTheApplication(): Unit = {
    gateway.listener.onFatal("Fluxer rejected the bot token (Gateway close 4004). Check fluxer.token.")
    assertEquals(1, errors)
  }
}

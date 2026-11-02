package wowchat.fluxer

import org.junit.Assert._
import org.junit.{After, Before, Test}
import wowchat.chat.{ChatChannel, ChatUser, IncomingChatMessage}
import wowchat.fluxer.FluxerTestSupport._
import wowchat.fluxer.Json._

class FluxerTransportTest {

  private val discovery =
    """{"endpoints":{"api_public":"https://api.example.invalid","gateway":"wss://gateway.example.invalid"}}"""

  private val guild10 =
    """{"id":"10","roles":[{"id":"10","name":"@everyone"},{"id":"11","name":"Officers"}],
      |"channels":[{"id":"100","type":0,"name":"guild-chat"},{"id":"101","type":2,"name":"voice"}],
      |"emojis":[],"members":[]}""".stripMargin
  private val guild20 = """{"id":"20","roles":[],"channels":[{"id":"200","type":0,"name":"officers"}],"emojis":[],"members":[]}"""

  private var http: FakeHttp = _
  private var memberPages: Map[Option[String], String] = _
  private var gateway: FakeGatewayConnection = _
  private var listener: RecordingListener = _
  private var transport: FluxerTransport = _

  @Before
  def setUp(): Unit = {
    memberPages = Map(None -> "[]")
    http = new FakeHttp(request => {
      if (request.url.endsWith("/.well-known/fluxer")) {
        response(200, discovery)
      } else if (request.method == "POST") {
        response(200, """{"id":"1"}""")
      } else if (request.url.contains("/members")) {
        val after = "after=(\\d+)".r.findFirstMatchIn(request.url).map(_.group(1))
        response(200, memberPages.getOrElse(after, "[]"))
      } else {
        response(404)
      }
    })
    listener = new RecordingListener
    transport = newTransport(FluxerConfig(Token, Some("https://chat.example.invalid")))
  }

  @After
  def tearDown(): Unit = transport.shutdown()

  private def newTransport(config: FluxerConfig, timings: FluxerTransport.Timings = FluxerTransport.Timings(5000, 100, 2)) = {
    new FluxerTransport(config, http, (url, token, l) => {
      gateway = new FakeGatewayConnection(url, token, l)
      gateway
    }, _ => (), timings)
  }

  private def eventually(description: String)(condition: => Boolean): Unit = {
    val deadline = System.currentTimeMillis + 5000
    while (!condition && System.currentTimeMillis < deadline) Thread.sleep(10)
    assertTrue(description, condition)
  }

  private def startAndLoad(): Unit = {
    transport.start(listener)
    gateway.dispatch("READY", """{"session_id":"s","user":{"id":"900"},"guilds":[{"id":"10","unavailable":true},{"id":"20","unavailable":true}]}""")
    gateway.dispatch("GUILD_CREATE", guild10)
    gateway.dispatch("GUILD_CREATE", guild20)
  }

  private def message(overrides: String = "", content: String = "hello"): String = {
    val base = s""""id":"5","channel_id":"100","guild_id":"10","channel_type":0,"type":0,"content":"$content",
      |"author":{"id":"7","username":"gwen","global_name":"Gwen"},"member":{"nick":"G"},
      |"mentions":[],"mention_roles":[],"attachments":[]""".stripMargin
    s"{$base${if (overrides.nonEmpty) "," + overrides else ""}}"
  }

  private def posts = http.requests.filter(_.method == "POST")

  // ---- startup ----

  @Test
  def startDiscoversAndConnects(): Unit = {
    transport.start(listener)
    assertTrue(gateway.started)
    assertEquals("wss://gateway.example.invalid", gateway.url)
    assertEquals(Token, gateway.token)
  }

  @Test
  def missingTokenIsFatal(): Unit = {
    transport = newTransport(FluxerConfig(" ", Some("https://chat.example.invalid")))
    transport.start(listener)
    assertEquals(Seq("fluxer.token is not set."), listener.fatalErrors)
    assertTrue(http.requests.isEmpty)
  }

  @Test
  def discoveryFailureIsFatal(): Unit = {
    http = FakeHttp.sequence(response(404))
    transport = newTransport(FluxerConfig(Token, Some("https://chat.example.invalid")))
    transport.start(listener)
    assertEquals(1, listener.fatalErrors.size)
    assertTrue(listener.fatalErrors.head.contains("returned HTTP 404"))
  }

  @Test
  def gatewayFatalErrorIsReported(): Unit = {
    transport.start(listener)
    gateway.listener.onFatal("Fluxer rejected the bot token (Gateway close 4004). Check fluxer.token.")
    assertEquals(1, listener.fatalErrors.size)
  }

  // ---- routing ----

  @Test
  def channelsAreAnnouncedOnceEveryGuildHasArrived(): Unit = {
    transport.start(listener)
    gateway.dispatch("READY", """{"session_id":"s","user":{"id":"900"},"guilds":[{"id":"10","unavailable":true},{"id":"20","unavailable":true}]}""")
    gateway.dispatch("GUILD_CREATE", guild10)
    assertTrue(listener.channelAnnouncements.isEmpty)
    gateway.dispatch("GUILD_DELETE", """{"id":"20","unavailable":true}""")
    assertEquals(Seq(Seq(ChatChannel("100", "guild-chat"))), listener.channelAnnouncements)
  }

  @Test
  def readyWithoutGuildsAnnouncesImmediately(): Unit = {
    transport.start(listener)
    gateway.dispatch("READY", """{"session_id":"s","user":{"id":"900"},"guilds":[]}""")
    assertEquals(Seq(Seq.empty), listener.channelAnnouncements)
  }

  @Test
  def missingGuildsDoNotBlockRoutingForever(): Unit = {
    transport = newTransport(FluxerConfig(Token, Some("https://chat.example.invalid")), FluxerTransport.Timings(50, 100, 2))
    transport.start(listener)
    gateway.dispatch("READY", """{"session_id":"s","user":{"id":"900"},"guilds":[{"id":"10","unavailable":true},{"id":"20","unavailable":true}]}""")
    gateway.dispatch("GUILD_CREATE", guild10)
    eventually("timeout announcement")(listener.channelAnnouncements.nonEmpty)
    assertEquals(Seq(ChatChannel("100", "guild-chat")), listener.channelAnnouncements.head)
  }

  @Test
  def channelChangesRefreshRouting(): Unit = {
    startAndLoad()
    assertEquals(1, listener.channelAnnouncements.size)
    gateway.dispatch("CHANNEL_UPDATE", """{"id":"100","type":0,"name":"renamed","guild_id":"10"}""")
    assertEquals(2, listener.channelAnnouncements.size)
    assertTrue(listener.channelAnnouncements.last.contains(ChatChannel("100", "renamed")))
    // unrelated changes do not
    gateway.dispatch("GUILD_ROLE_CREATE", """{"guild_id":"10","role":{"id":"12","name":"x"}}""")
    assertEquals(2, listener.channelAnnouncements.size)
  }

  @Test
  def newSessionAfterReadyAnnouncesAgain(): Unit = {
    startAndLoad()
    gateway.dispatch("READY", """{"session_id":"s2","user":{"id":"900"},"guilds":[{"id":"10","unavailable":true}]}""")
    gateway.dispatch("GUILD_CREATE", guild10)
    assertEquals(2, listener.channelAnnouncements.size)
  }

  // ---- incoming messages ----

  @Test
  def messagesAreConvertedForTheBridge(): Unit = {
    startAndLoad()
    gateway.dispatch("MESSAGE_CREATE", message(
      """"attachments":[{"id":"1","url":"https://media.invalid/a.png"},{"id":"2","url":null}],"mentions":[{"id":"8","username":"hal","global_name":"Hal"}]""",
      content = "hi <@8> <@&11> <#100>"))
    assertEquals(Seq(IncomingChatMessage(ChatChannel("100", "guild-chat"), ChatUser("7", "G"),
      "hi @Hal @Officers #guild-chat", Seq("https://media.invalid/a.png"))), listener.messages)
  }

  @Test
  def displayNameFallsBackToGlobalNameThenUsername(): Unit = {
    startAndLoad()
    gateway.dispatch("MESSAGE_CREATE", message(""""member":{"nick":null}"""))
    gateway.dispatch("MESSAGE_CREATE", message(""""member":{},"author":{"id":"8","username":"hal"}"""))
    assertEquals(Seq("Gwen", "hal"), listener.messages.map(_.author.displayName))
  }

  @Test
  def messagesThatMustNotBeRelayedAreDropped(): Unit = {
    startAndLoad()
    Seq(
      message(""""author":{"id":"900","username":"wowchat"}"""), // the bot itself
      message(""""channel_type":2"""),                            // voice channel chat
      message(""""type":7"""),                                    // USER_JOIN
      message(""""channel_id":"101""""),                          // not a cached text channel
      message(""""guild_id":null"""),                             // direct message
      message(""""author":"nope""""),                             // malformed
      message(""""type":"0"""")                                   // malformed type
    ).foreach(m => gateway.dispatch("MESSAGE_CREATE", m))
    assertTrue(listener.messages.isEmpty)

    // a reply is relayed
    gateway.dispatch("MESSAGE_CREATE", message(""""type":19"""))
    assertEquals(1, listener.messages.size)
  }

  @Test
  def unexpectedEventsAreIgnored(): Unit = {
    startAndLoad()
    gateway.dispatch("SOMETHING_NEW", """{"x":1}""")
    gateway.dispatch("GUILD_MEMBER_ADD", "[]")
    gateway.dispatch("MESSAGE_CREATE", "null")
    assertTrue(listener.messages.isEmpty)
    assertEquals(1, listener.channelAnnouncements.size)
  }

  // ---- outgoing messages ----

  @Test
  def postMessageUsesHttp(): Unit = {
    transport.start(listener)
    transport.postMessage(ChatChannel("100", "guild-chat"), "hello")
    eventually("message posted")(posts.nonEmpty)
    assertEquals("https://api.example.invalid/v1/channels/100/messages", posts.head.url)
    assertEquals(Some("hello"), json(posts.head.body.get).str("content"))
  }

  @Test
  def sendingContinuesWhileTheGatewayIsDisconnected(): Unit = {
    startAndLoad()
    gateway.listener.onDisconnected(1006, "")
    assertEquals(1, listener.disconnects)
    transport.postMessage(ChatChannel("100", "guild-chat"), "during outage")
    eventually("message posted during the outage")(posts.size == 1)

    // even after the Gateway gave up entirely
    gateway.listener.onFatal("gone")
    transport.postMessage(ChatChannel("100", "guild-chat"), "after fatal")
    eventually("message posted after the Gateway stopped")(posts.size == 2)
  }

  @Test
  def postBeforeStartIsRejectedWithoutThrowing(): Unit = {
    transport.postMessage(ChatChannel("100", "guild-chat"), "too early")
    assertTrue(http.requests.isEmpty)
  }

  @Test
  def transportFormattingUsesTheCache(): Unit = {
    startAndLoad()
    assertEquals("<@&11>", transport.resolveMentions(ChatChannel("100", "guild-chat"), "@officers", _ => ()))
    assertEquals(4000, transport.maxMessageLength)
    assertEquals("a\\_b <:x_y:1>", transport.escapeFormatting("a_b <:x_y:1>"))
  }

  // ---- presence ----

  private def presences = gateway.synchronized(gateway.sent.filter(_._2 == "Presence Update").map(p => json(p._1)))

  private def presenceText(payload: com.fasterxml.jackson.databind.JsonNode) = payload.obj("d").flatMap(_.obj("custom_status")).flatMap(_.str("text"))

  @Test
  def statusUpdatesAreCoalesced(): Unit = {
    transport.start(listener)
    transport.setStatus("a")
    eventually("first status")(presences.size == 1)
    transport.setStatus("b")
    transport.setStatus("c")
    eventually("latest status")(presences.size == 2)
    Thread.sleep(250)
    assertEquals(Seq(Some("a"), Some("c")), presences.map(presenceText))
  }

  @Test
  def statusIsPublishedAgainForANewSession(): Unit = {
    transport.start(listener)
    transport.setStatus("Test Realm")
    eventually("status")(presences.size == 1)
    gateway.dispatch("READY", """{"session_id":"s","user":{"id":"900"},"guilds":[]}""")
    eventually("status after READY")(presences.size == 2)
    gateway.dispatch("RESUMED", "{}")
    eventually("status after RESUMED")(presences.size == 3)
    assertTrue(presences.forall(p => presenceText(p).contains("Test Realm")))
  }

  // ---- member list ----

  @Test
  def memberListIsLoadedPageByPage(): Unit = {
    memberPages = Map(
      None -> """[{"user":{"id":"1","username":"ada","global_name":"Ada"},"nick":null},{"user":{"id":"2","username":"bob"}}]""",
      Some("2") -> """[{"user":{"id":"3","username":"cy"}}]"""
    )
    startAndLoad()
    eventually("members loaded")(transport.snapshot.guilds("10").members.size == 3)
    val memberRequests = http.requests.filter(_.url.contains("/guilds/10/members"))
    assertEquals(Seq("https://api.example.invalid/v1/guilds/10/members?limit=2",
      "https://api.example.invalid/v1/guilds/10/members?limit=2&after=2"), memberRequests.map(_.url))
    assertEquals("<@1>", transport.resolveMentions(ChatChannel("100", "guild-chat"), "@ada", _ => ()))
  }

  @Test
  def memberListFailureIsNotFatal(): Unit = {
    http = new FakeHttp(request => {
      if (request.url.endsWith("/.well-known/fluxer")) response(200, discovery) else response(403, """{"code":"MISSING_PERMISSIONS","message":"no"}""")
    })
    transport = newTransport(FluxerConfig(Token, Some("https://chat.example.invalid")))
    startAndLoad()
    eventually("member requests made")(http.requests.count(_.url.contains("/members")) == 2)
    assertTrue(listener.fatalErrors.isEmpty)
    assertEquals(1, listener.channelAnnouncements.size)
  }

  @Test
  def shutdownStopsTheGateway(): Unit = {
    transport.start(listener)
    transport.shutdown()
    assertTrue(gateway.stopped)
  }
}

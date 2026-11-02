package wowchat

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.{CountDownLatch, TimeUnit}

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import io.netty.channel.nio.NioEventLoopGroup
import org.junit.Assert._
import org.junit.{After, Before, Test}
import wowchat.chat.{ChatChannel, ChatPlatform}
import wowchat.common.{CommonConnectionCallback, Global}
import wowchat.fluxer.Json._
import wowchat.fluxer._

class WoWChatStartupTest {

  private val gameConnects = new CountDownLatch(1)
  private val gameController = new CommonConnectionCallback {
    override def connect: Unit = gameConnects.countDown()
  }

  private var gatewayServer: FakeGatewayServer = _
  private var discoveryServer: HttpServer = _

  @Before
  def setUp(): Unit = {
    TestSupport.resetGlobals()
    Global.group = null
  }

  @After
  def tearDown(): Unit = {
    WoWChat.shutdown()
    Option(gatewayServer).foreach(_.stop())
    Option(discoveryServer).foreach(_.stop(0))
    TestSupport.resetGlobals()
    Global.group = null
  }

  // Serves /.well-known/fluxer pointing at the local Gateway stand-in.
  private def startDiscovery(gatewayPort: Int): String = {
    discoveryServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    discoveryServer.createContext("/.well-known/fluxer", new HttpHandler {
      override def handle(exchange: HttpExchange): Unit = {
        val body = s"""{"endpoints":{"api_public":"http://127.0.0.1:1","gateway":"ws://127.0.0.1:$gatewayPort"}}"""
          .getBytes(StandardCharsets.UTF_8)
        exchange.getResponseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, body.length)
        exchange.getResponseBody.write(body)
        exchange.close()
      }
    })
    discoveryServer.start()
    s"http://127.0.0.1:${discoveryServer.getAddress.getPort}"
  }

  @Test
  def startsWithValidConfigurationAndShutsDownCleanly(): Unit = {
    gatewayServer = new FakeGatewayServer
    val instanceUrl = startDiscovery(gatewayServer.port)
    Global.config = Global.config.copy(fluxer = Global.config.fluxer.copy(instanceUrl = Some(instanceUrl)))
    val wowGroup = new NioEventLoopGroup(1)
    Global.group = wowGroup

    WoWChat.startChat(gameController)

    val connection = gatewayServer.nextConnection()
    gatewayServer.send(connection, """{"op":10,"d":{"heartbeat_interval":30000}}""")
    val identify = gatewayServer.nextPayload(Opcodes.Identify)
    assertEquals(Some(Global.config.fluxer.token), identify.obj("d").flatMap(_.str("token")))

    gatewayServer.send(connection, """{"op":0,"t":"READY","s":1,"d":{"session_id":"s","user":{"id":"900"},"guilds":[{"id":"10","unavailable":true}]}}""")
    gatewayServer.send(connection, """{"op":0,"t":"GUILD_CREATE","s":2,"d":{"id":"10","roles":[],"emojis":[],"members":[],
      |"channels":[{"id":"100","type":0,"name":"guild-chat"}]}}""".stripMargin)

    // test.conf relays guild chat to "Guild-Chat", so the WoW connection starts now
    assertTrue("the WoW connection was started", gameConnects.await(5, TimeUnit.SECONDS))
    assertTrue(Global.chat.shouldForwardWowChat(new wowchat.game.GamePackets {}.ChatEvents.CHAT_MSG_GUILD, None))

    WoWChat.shutdown()
    connection.channel.closeFuture().await(5, TimeUnit.SECONDS)
    assertFalse("the Fluxer connection is closed", connection.channel.isActive)
    assertTrue("the WoW event loop is shut down", wowGroup.isTerminated)
    // nothing reconnects after shutdown
    assertNull(gatewayServer.connections.poll(1500, TimeUnit.MILLISECONDS))
  }

  @Test
  def discoveryFailureIsReportedAsFatal(): Unit = {
    val errors = new CountDownLatch(1)
    val listener = new RecordingListener
    // nothing listens on port 1
    val transport = new FluxerTransport(FluxerConfig(Global.config.fluxer.token, Some("http://127.0.0.1:1")), discoverySleeper = _ => ())
    transport.start(new RecordingListener {
      override def onFatalError(reason: String): Unit = {
        listener.onFatalError(reason)
        errors.countDown()
      }
    })
    assertTrue(errors.await(5, TimeUnit.SECONDS))
    assertTrue(listener.fatalErrors.head, listener.fatalErrors.head.contains("discovery"))
    transport.shutdown()
  }

  @Test
  def shutdownWithNothingStartedIsSafe(): Unit = {
    Global.chat = null
    Global.group = null
    WoWChat.shutdown()
    WoWChat.shutdown()
  }

  @Test
  def shutdownClosesWowEvenIfChatFails(): Unit = {
    val wowGroup = new NioEventLoopGroup(1)
    Global.group = wowGroup
    Global.chat = new ChatPlatform {
      override def shouldForwardWowChat(tp: Byte, wowChannel: Option[String]): Boolean = false
      override def sendMessageFromWow(from: Option[String], message: String, wowType: Byte, wowChannel: Option[String]): Unit = ()
      override def sendGuildNotification(eventKey: String, message: String): Unit = ()
      override def sendAchievementNotification(name: String, achievementId: Int): Unit = ()
      override def sendMessage(channel: ChatChannel, message: String): Unit = ()
      override def changeGuildStatus(message: String): Unit = ()
      override def changeRealmStatus(message: String): Unit = ()
      override def shutdown(): Unit = throw new IllegalStateException("boom")
    }
    WoWChat.shutdown()
    assertTrue(wowGroup.isTerminated)
  }
}

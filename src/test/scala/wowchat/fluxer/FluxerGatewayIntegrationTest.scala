package wowchat.fluxer

import java.net.InetSocketAddress
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}

import com.fasterxml.jackson.databind.JsonNode
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel._
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.websocketx._
import io.netty.handler.codec.http.{HttpObjectAggregator, HttpServerCodec}
import org.junit.Assert._
import org.junit.{After, Before, Test}
import wowchat.common.Global
import wowchat.fluxer.FluxerTestSupport._
import wowchat.fluxer.Json._

// One client WebSocket accepted by FakeGatewayServer, with the request URI it connected to.
case class Connection(channel: Channel, uri: String)

// A minimal stand-in for the Fluxer Gateway on localhost, speaking the documented protocol, used to exercise the
// real Netty client end to end.
class FakeGatewayServer {

  private val group = new NioEventLoopGroup(1)
  val connections = new LinkedBlockingQueue[Connection]()
  val received = new LinkedBlockingQueue[JsonNode]()
  // Acknowledge heartbeats like the real Gateway does.
  @volatile var autoAck = true

  private val serverChannel = new ServerBootstrap()
    .group(group)
    .channel(classOf[NioServerSocketChannel])
    .childHandler(new ChannelInitializer[SocketChannel] {
      override def initChannel(ch: SocketChannel): Unit = {
        ch.pipeline()
          .addLast(new HttpServerCodec())
          .addLast(new HttpObjectAggregator(65536))
          .addLast(new WebSocketServerProtocolHandler("/", null, false, 65536, false, true))
          .addLast(new SimpleChannelInboundHandler[WebSocketFrame] {
            override def userEventTriggered(ctx: ChannelHandlerContext, evt: Any): Unit = {
              evt match {
                case complete: WebSocketServerProtocolHandler.HandshakeComplete =>
                  connections.put(Connection(ctx.channel(), complete.requestUri()))
                case _ =>
              }
              super.userEventTriggered(ctx, evt)
            }

            override def channelRead0(ctx: ChannelHandlerContext, frame: WebSocketFrame): Unit = frame match {
              case text: TextWebSocketFrame =>
                val payload = json(text.text())
                if (autoAck && payload.int("op").contains(Opcodes.Heartbeat)) {
                  ctx.writeAndFlush(new TextWebSocketFrame("""{"op":11}"""))
                }
                received.put(payload)
              case _ =>
            }
          })
      }
    })
    .bind("127.0.0.1", 0).sync().channel()

  val port: Int = serverChannel.localAddress().asInstanceOf[InetSocketAddress].getPort

  def nextConnection(): Connection = {
    Option(connections.poll(5, TimeUnit.SECONDS)).getOrElse(throw new AssertionError("no Gateway connection"))
  }

  // Next client payload with the given opcode, skipping others (such as heartbeats).
  def nextPayload(op: Int, timeoutMs: Long = 5000): JsonNode = {
    val deadline = System.currentTimeMillis + timeoutMs
    while (System.currentTimeMillis < deadline) {
      val payload = received.poll(deadline - System.currentTimeMillis, TimeUnit.MILLISECONDS)
      if (payload != null && payload.int("op").contains(op)) {
        return payload
      }
    }
    throw new AssertionError(s"no payload with op $op")
  }

  def send(connection: Connection, text: String): Unit = connection.channel.writeAndFlush(new TextWebSocketFrame(text)).sync()

  def close(connection: Connection, code: Int, reason: String): Unit = {
    connection.channel.writeAndFlush(new CloseWebSocketFrame(code, reason)).addListener(ChannelFutureListener.CLOSE).sync()
  }

  def stop(): Unit = group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync()
}

class FluxerGatewayIntegrationTest {

  private var server: FakeGatewayServer = _
  private var gateway: FluxerGateway = _
  private val events = new LinkedBlockingQueue[String]()
  private val disconnects = new LinkedBlockingQueue[Int]()
  private val fatal = new LinkedBlockingQueue[String]()

  private val listener = new GatewayListener {
    override def onDispatch(event: String, data: JsonNode): Unit = events.put(event)
    override def onDisconnected(code: Int, reason: String): Unit = disconnects.put(code)
    override def onFatal(reason: String): Unit = fatal.put(reason)
  }

  @Before
  def setUp(): Unit = {
    server = new FakeGatewayServer
    gateway = new FluxerGateway(s"ws://127.0.0.1:${server.port}", Token, listener)
  }

  @After
  def tearDown(): Unit = {
    gateway.shutdown()
    server.stop()
  }

  private def hello(connection: Connection, intervalMs: Int = 300): Unit = {
    server.send(connection, s"""{"op":10,"d":{"heartbeat_interval":$intervalMs}}""")
  }

  private def nextEvent(): String = Option(events.poll(5, TimeUnit.SECONDS)).getOrElse(throw new AssertionError("no event"))

  private def connectAndReady(): Connection = {
    gateway.start()
    val connection = server.nextConnection()
    hello(connection)
    server.nextPayload(Opcodes.Identify)
    server.send(connection, """{"op":0,"t":"READY","s":1,"d":{"session_id":"sess-1","user":{"id":"900"},"guilds":[]}}""")
    assertEquals("READY", nextEvent())
    connection
  }

  @Test
  def identifiesWithConnectionParametersAndHeartbeats(): Unit = {
    gateway.start()
    val connection = server.nextConnection()
    assertTrue(connection.uri, connection.uri.contains("v=1&encoding=json"))
    hello(connection)

    val identify = server.nextPayload(Opcodes.Identify)
    assertEquals(Some(Token), identify.obj("d").flatMap(_.str("token")))

    val heartbeat = server.nextPayload(Opcodes.Heartbeat)
    assertTrue(heartbeat.get("d").isNull)

    server.send(connection, """{"op":0,"t":"READY","s":1,"d":{"session_id":"sess-1","user":{"id":"900"},"guilds":[]}}""")
    assertEquals("READY", nextEvent())
    assertEquals(Some("900"), gateway.selfUserId)

    // heartbeats after READY carry its sequence (one sent while READY was in flight may still be null)
    val deadline = System.currentTimeMillis + 5000
    var sequence: Option[Long] = None
    while (sequence.isEmpty && System.currentTimeMillis < deadline) {
      sequence = server.nextPayload(Opcodes.Heartbeat).long("d")
    }
    assertEquals(Some(1L), sequence)
    // the socket stayed up the whole time, because every heartbeat was acknowledged
    assertTrue(connection.channel.isActive)
  }

  @Test
  def resumesAfterServerCloseThenTreatsBadTokenAsFatal(): Unit = {
    val first = connectAndReady()
    server.send(first, """{"op":0,"t":"MESSAGE_CREATE","s":2,"d":{"id":"5"}}""")
    assertEquals("MESSAGE_CREATE", nextEvent())

    server.close(first, 4000, "Unknown error")
    assertEquals(4000, disconnects.poll(5, TimeUnit.SECONDS))

    val second = server.nextConnection()
    hello(second)
    val resume = server.nextPayload(Opcodes.Resume)
    assertEquals(Some("sess-1"), resume.obj("d").flatMap(_.str("session_id")))
    assertEquals(Some(2L), resume.obj("d").flatMap(_.long("seq")))
    assertEquals(Some(Token), resume.obj("d").flatMap(_.str("token")))

    server.send(second, """{"op":0,"t":"RESUMED","s":2,"d":{}}""")
    assertEquals("RESUMED", nextEvent())

    // commands are sent once the session is ready again
    gateway.sendWhenReady(FluxerPayloads.presence(Some("Test Realm")), "Presence Update")
    val presence = server.nextPayload(Opcodes.PresenceUpdate)
    assertEquals(Some("Test Realm"), presence.obj("d").flatMap(_.obj("custom_status")).flatMap(_.str("text")))

    server.close(second, 4004, "Invalid token")
    assertNotNull("4004 is fatal", fatal.poll(5, TimeUnit.SECONDS))
    // and nothing reconnects
    assertNull(server.connections.poll(2000, TimeUnit.MILLISECONDS))
  }

  @Test
  def reconnectsWhenHeartbeatsAreNotAcknowledged(): Unit = {
    server.autoAck = false
    gateway.start()
    val first = server.nextConnection()
    hello(first, 150)
    server.nextPayload(Opcodes.Identify)
    server.send(first, """{"op":0,"t":"READY","s":1,"d":{"session_id":"sess-1","user":{"id":"900"},"guilds":[]}}""")
    // never acknowledge: the client must give up on this socket and resume on a new one
    val second = server.nextConnection()
    hello(second)
    assertEquals(Some("sess-1"), server.nextPayload(Opcodes.Resume).obj("d").flatMap(_.str("session_id")))
  }

  @Test
  def serverReconnectRequestResumesOnNewSocket(): Unit = {
    val first = connectAndReady()
    server.send(first, """{"op":7}""")
    val second = server.nextConnection()
    hello(second)
    server.nextPayload(Opcodes.Resume)
  }

  @Test
  def survivesTheWowEventLoopShuttingDown(): Unit = {
    val previous = Global.group
    val wowGroup = new NioEventLoopGroup(1)
    Global.group = wowGroup
    try {
      val connection = connectAndReady()
      assertNotSame(wowGroup, gateway.group)
      // what a WoW reconnect does
      Global.group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync()
      server.send(connection, """{"op":0,"t":"MESSAGE_CREATE","s":2,"d":{"id":"5"}}""")
      assertEquals("MESSAGE_CREATE", nextEvent())
      assertTrue(connection.channel.isActive)
    } finally {
      Global.group = previous
    }
  }

  @Test
  def shutdownClosesTheConnectionAndStopsItsEventLoop(): Unit = {
    val connection = connectAndReady()
    gateway.shutdown()
    assertTrue(gateway.group.isTerminated)
    connection.channel.closeFuture().await(5, TimeUnit.SECONDS)
    assertFalse(connection.channel.isActive)
    assertNull(server.connections.poll(1500, TimeUnit.MILLISECONDS))
  }

  @Test
  def unreachableGatewayKeepsRetrying(): Unit = {
    server.stop()
    gateway.start()
    // nothing listens on the port: the client reports nothing fatal and keeps trying with backoff
    assertNull(fatal.poll(1500, TimeUnit.MILLISECONDS))
  }

  @Test
  def oversizedCommandsAreNotSent(): Unit = {
    val connection = connectAndReady()
    gateway.sendWhenReady("x" * (FluxerPayloads.MaxPayloadBytes + 1), "Oversized")
    gateway.sendWhenReady(FluxerPayloads.presence(Some("ok")), "Presence Update")
    server.nextPayload(Opcodes.PresenceUpdate)
    assertTrue(connection.channel.isActive)
  }
}

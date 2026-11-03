package wowchat.fluxer

import java.net.URI
import java.util.concurrent.{ThreadLocalRandom, TimeUnit}

import com.fasterxml.jackson.databind.JsonNode
import com.typesafe.scalalogging.StrictLogging
import io.netty.bootstrap.Bootstrap
import io.netty.channel._
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http.websocketx._
import io.netty.handler.codec.http.{HttpClientCodec, HttpObjectAggregator}
import io.netty.handler.ssl.{SslContext, SslContextBuilder, SslHandler}
import io.netty.util.concurrent.ScheduledFuture

import scala.util.Try

trait GatewayListener {
  def onDispatch(event: String, data: JsonNode): Unit
  def onDisconnected(code: Int, reason: String): Unit
  def onFatal(reason: String): Unit
}

trait GatewayConnection {
  def start(): Unit
  // Sends a command if the session is ready (after READY or RESUMED); otherwise the command is dropped.
  // Asynchronous and safe to call from any thread.
  def sendWhenReady(payload: String, description: String): Unit
  def selfUserId: Option[String]
  def shutdown(): Unit
}

object FluxerGateway {

  // Inbound frames can be large (Guild Create for a big guild).
  val MaxInboundFrameBytes: Int = 64 * 1024 * 1024

  // Adds the connection parameters, keeping any query the discovered URL already has.
  def connectionUri(gatewayUrl: String): URI = {
    val base = new URI(gatewayUrl.trim)
    val params = "v=1&encoding=json"
    val query = Option(base.getRawQuery).filter(_.nonEmpty).fold(params)(q => s"$q&$params")
    val path = Option(base.getRawPath).filter(_.nonEmpty).getOrElse("/")
    new URI(s"${base.getScheme}://${base.getRawAuthority}$path?$query")
  }
}

// Netty WebSocket connection to the Fluxer Gateway. Everything runs on one dedicated event loop thread, separate
// from the WoW connection's event loop, so a WoW reconnect or shutdown never touches it.
class FluxerGateway(gatewayUrl: String,
                    token: String,
                    listener: GatewayListener,
                    sessionFactory: () => FluxerGatewaySession) extends GatewayConnection with StrictLogging {

  def this(gatewayUrl: String, token: String, listener: GatewayListener) =
    this(gatewayUrl, token, listener, () => new FluxerGatewaySession(token))

  import FluxerGateway._

  private val uri = connectionUri(gatewayUrl)
  private val secure = uri.getScheme.equalsIgnoreCase("wss")
  private val host = uri.getHost
  private val port = if (uri.getPort != -1) uri.getPort else if (secure) 443 else 80
  private val sslContext: Option[SslContext] = if (secure) Some(SslContextBuilder.forClient().build()) else None

  private[fluxer] val group = new NioEventLoopGroup(1, FluxerRestClient.daemonThreads("fluxer-gateway"))
  private val loop = group.next()
  private val session = sessionFactory()

  // Only touched on the event loop thread.
  private var channel: Option[Channel] = None
  private var generation = 0
  private var heartbeat: Option[ScheduledFuture[_]] = None
  @volatile private var stopped = false

  override def selfUserId: Option[String] = session.selfUserId

  override def start(): Unit = {
    loop.execute(new Runnable {
      override def run(): Unit = connect()
    })
  }

  override def sendWhenReady(payload: String, description: String): Unit = {
    if (!stopped) {
      Try(loop.execute(new Runnable {
        override def run(): Unit = {
          if (session.isReady && channel.exists(_.isActive)) {
            write(payload, description)
          } else {
            logger.debug(s"Not sending $description: the Fluxer Gateway session is not ready.")
          }
        }
      }))
    }
  }

  override def shutdown(): Unit = {
    if (!stopped) {
      stopped = true
      Try(loop.submit(new Runnable {
        override def run(): Unit = {
          cancelHeartbeat()
          generation += 1
          channel.foreach(c => {
            if (c.isActive) {
              c.writeAndFlush(new CloseWebSocketFrame(1000, "shutdown")).addListener(ChannelFutureListener.CLOSE)
            }
          })
          channel = None
        }
      }).await(2, TimeUnit.SECONDS))
      group.shutdownGracefully(0, 2, TimeUnit.SECONDS).awaitUninterruptibly(5, TimeUnit.SECONDS)
      logger.info("Fluxer Gateway connection closed.")
    }
  }

  private def connect(): Unit = {
    if (stopped) {
      return
    }
    generation += 1
    val currentGeneration = generation
    logger.info(s"Connecting to the Fluxer Gateway at ${uri.getScheme}://$host:$port...")

    val handshaker = WebSocketClientHandshakerFactory.newHandshaker(
      uri, WebSocketVersion.V13, null, false, new io.netty.handler.codec.http.DefaultHttpHeaders(), MaxInboundFrameBytes)

    val bootstrap = new Bootstrap()
      .group(group)
      .channel(classOf[NioSocketChannel])
      .option[Integer](ChannelOption.CONNECT_TIMEOUT_MILLIS, 15000)
      .handler(new ChannelInitializer[SocketChannel] {
        override def initChannel(ch: SocketChannel): Unit = {
          sslContext.foreach(context => {
            val engine = context.newEngine(ch.alloc(), host, port)
            // Netty does not check the certificate's host name unless asked to.
            val parameters = engine.getSSLParameters
            parameters.setEndpointIdentificationAlgorithm("HTTPS")
            engine.setSSLParameters(parameters)
            ch.pipeline().addLast("ssl", new SslHandler(engine))
          })
          ch.pipeline()
            .addLast("http", new HttpClientCodec())
            .addLast("aggregator", new HttpObjectAggregator(65536))
            .addLast("websocket", new WebSocketClientProtocolHandler(handshaker, false, true, 15000))
            .addLast("frames", new WebSocketFrameAggregator(MaxInboundFrameBytes))
            .addLast("gateway", new GatewayHandler(currentGeneration))
        }
      })

    bootstrap.connect(host, port).addListener(new ChannelFutureListener {
      override def operationComplete(future: ChannelFuture): Unit = {
        if (future.isSuccess) {
          if (currentGeneration == generation && !stopped) {
            channel = Some(future.channel())
          } else {
            future.channel().close()
          }
        } else if (currentGeneration == generation) {
          logger.warn(s"Could not connect to the Fluxer Gateway: ${safeMessage(future.cause())}")
          run(session.onConnectFailed())
        }
      }
    })
  }

  private class GatewayHandler(handlerGeneration: Int) extends SimpleChannelInboundHandler[WebSocketFrame] {

    private var closeCode: Option[Int] = None
    private var closeReason = ""

    private def current: Boolean = handlerGeneration == generation && !stopped

    override def userEventTriggered(ctx: ChannelHandlerContext, evt: Any): Unit = {
      evt match {
        case WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE if current =>
          logger.info("Connected to the Fluxer Gateway.")
          run(session.onSocketOpened())
        case WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_TIMEOUT if current =>
          logger.warn("The Fluxer Gateway WebSocket handshake timed out.")
        case _ =>
      }
      super.userEventTriggered(ctx, evt)
    }

    override def channelRead0(ctx: ChannelHandlerContext, frame: WebSocketFrame): Unit = {
      if (!current) {
        return
      }
      frame match {
        case text: TextWebSocketFrame =>
          run(session.onText(text.text()))
        case close: CloseWebSocketFrame =>
          closeCode = Some(close.statusCode()).filter(_ > 0)
          closeReason = Option(close.reasonText()).getOrElse("")
          ctx.close()
        case _: BinaryWebSocketFrame =>
          // Compression is never requested, so the Gateway sends text frames.
          logger.warn("Ignoring an unexpected binary frame from the Fluxer Gateway.")
        case _ =>
      }
    }

    override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit = {
      if (current) {
        logger.warn(s"Fluxer Gateway connection error: ${safeMessage(cause)}")
      }
      ctx.close()
    }

    override def channelInactive(ctx: ChannelHandlerContext): Unit = {
      if (current) {
        val code = closeCode.getOrElse(CloseCodes.Abnormal)
        val reason = Redaction.redact(closeReason, token)
        logger.warn(s"Disconnected from the Fluxer Gateway: $code ${CloseCodes.describe(code)}${if (reason.nonEmpty) s" ($reason)" else ""}")
        cancelHeartbeat()
        channel = None
        notifyListener(listener.onDisconnected(code, reason))
        run(session.onSocketClosed(code, reason))
      }
      super.channelInactive(ctx)
    }
  }

  private def run(actions: Seq[GatewayAction]): Unit = {
    actions.foreach {
      case GatewayAction.Send(payload, description) =>
        write(payload, description)

      case GatewayAction.StartHeartbeat(intervalMs) =>
        cancelHeartbeat()
        // The first heartbeat goes out at a random point within the first interval, then once every interval.
        val firstDelay = (intervalMs * ThreadLocalRandom.current().nextDouble()).toLong
        heartbeat = Some(loop.scheduleAtFixedRate(new Runnable {
          override def run(): Unit = FluxerGateway.this.run(session.onHeartbeatTimer())
        }, firstDelay, intervalMs, TimeUnit.MILLISECONDS))

      case GatewayAction.Dispatch(event, data) =>
        notifyListener(listener.onDispatch(event, data))

      case GatewayAction.Reconnect(delayMs, reason) =>
        cancelHeartbeat()
        // Invalidate the old socket so its close is not handled a second time.
        generation += 1
        channel.foreach(c => {
          if (c.isActive) {
            notifyListener(listener.onDisconnected(CloseCodes.Abnormal, reason))
            c.close()
          }
        })
        channel = None
        if (!stopped) {
          logger.info(s"Reconnecting to the Fluxer Gateway in ${delayMs}ms ($reason).")
          loop.schedule(new Runnable {
            override def run(): Unit = connect()
          }, delayMs, TimeUnit.MILLISECONDS)
        }

      case GatewayAction.Fatal(reason) =>
        logger.error(reason)
        stopped = true
        cancelHeartbeat()
        channel.foreach(_.close())
        channel = None
        notifyListener(listener.onFatal(reason))
    }
  }

  private def write(payload: String, description: String): Boolean = {
    channel.filter(_.isActive) match {
      case Some(c) =>
        if (FluxerPayloads.byteLength(payload) > FluxerPayloads.MaxPayloadBytes) {
          logger.error(s"Not sending $description to the Fluxer Gateway: it is larger than ${FluxerPayloads.MaxPayloadBytes} bytes.")
          false
        } else {
          logger.debug(s"Sending $description to the Fluxer Gateway.")
          c.writeAndFlush(new TextWebSocketFrame(payload))
          true
        }
      case None =>
        logger.debug(s"Not sending $description: not connected to the Fluxer Gateway.")
        false
    }
  }

  private def cancelHeartbeat(): Unit = {
    heartbeat.foreach(_.cancel(false))
    heartbeat = None
  }

  private def notifyListener(f: => Unit): Unit = {
    Try(f).failed.foreach(e => logger.error(s"Error while handling a Fluxer Gateway event: ${safeMessage(e)}"))
  }

  private def safeMessage(e: Throwable): String = {
    Redaction.redact(s"${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}", token)
  }
}

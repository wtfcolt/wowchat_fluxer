package wowchat

import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}

import wowchat.chat.{ChatBridge, ChatTransport}
import wowchat.common.{CommonConnectionCallback, Global, ReconnectDelay, WowChatConfig}
import wowchat.fluxer.FluxerTransport
import wowchat.game.GameConnector
import wowchat.realm.{RealmConnectionCallback, RealmConnector}
import com.typesafe.scalalogging.StrictLogging
import io.netty.channel.nio.NioEventLoopGroup

import scala.util.Try

object WoWChat extends StrictLogging {

  private val RELEASE = "v1.4.0"

  // Released by the shutdown hook. The Fluxer client runs on daemon threads, so main waits on this to keep the
  // application running until it is stopped.
  private val stopped = new CountDownLatch(1)

  def main(args: Array[String]): Unit = {
    logger.info(s"Running WoWChat - $RELEASE")
    val confFile = if (args.nonEmpty) {
      args(0)
    } else {
      logger.info("No configuration file supplied. Trying with default wowchat.conf.")
      "wowchat.conf"
    }
    Global.config = Try(WowChatConfig(confFile)).fold(e => {
      logger.error(s"Invalid configuration in $confFile: ${e.getMessage}")
      sys.exit(1)
    }, identity)

    val gameConnectionController: CommonConnectionCallback = new CommonConnectionCallback {

      private val reconnectExecutor = Executors.newSingleThreadScheduledExecutor
      private val reconnectDelay = new ReconnectDelay

      override def connect: Unit = {
        Global.group = new NioEventLoopGroup

        val realmConnector = new RealmConnector(new RealmConnectionCallback {
          override def success(host: String, port: Int, realmName: String, realmId: Int, sessionKey: Array[Byte]): Unit = {
            gameConnect(host, port, realmName, realmId, sessionKey)
          }

          override def disconnected: Unit = doReconnect

          override def error: Unit = exitAsync(1)
        })

        realmConnector.connect
      }

      private def gameConnect(host: String, port: Int, realmName: String, realmId: Int, sessionKey: Array[Byte]): Unit = {
        new GameConnector(host, port, realmName, realmId, sessionKey, this).connect
      }

      override def connected: Unit = reconnectDelay.reset

      override def disconnected: Unit = doReconnect

      def doReconnect: Unit = {
        Global.group.shutdownGracefully()
        Global.chat.changeRealmStatus("Connecting...")
        val delay = reconnectDelay.getNext
        logger.info(s"Disconnected from server! Reconnecting in $delay seconds...")

        reconnectExecutor.schedule(new Runnable {
          override def run(): Unit = connect
        }, delay, TimeUnit.SECONDS)
      }
    }

    sys.addShutdownHook(shutdown())

    logger.info("Connecting to Fluxer...")
    startChat(gameConnectionController)
    stopped.await()
  }

  // Starts the chat side. Once the chat channels are known, gameConnectionController connects to WoW.
  private[wowchat] def startChat(gameConnectionController: CommonConnectionCallback,
                                 transport: => ChatTransport = new FluxerTransport(Global.config.fluxer)): ChatBridge = {
    val bridge = new ChatBridge(transport, new CommonConnectionCallback {
      override def connected: Unit = gameConnectionController.connect

      override def error: Unit = exitAsync(1)
    })
    Global.chat = bridge
    bridge.start()
    bridge
  }

  // Closes the chat connection (sending queued messages first) and the WoW connection. Safe to call more than once.
  private[wowchat] def shutdown(): Unit = {
    logger.info("Shutting down...")
    Option(Global.chat).foreach(chat => {
      Try(chat.shutdown()).failed.foreach(e => logger.error(s"Error while closing the chat connection: ${e.getMessage}"))
    })
    Option(Global.group).foreach(group => {
      Try(group.shutdownGracefully(0, 2, TimeUnit.SECONDS).awaitUninterruptibly(5, TimeUnit.SECONDS))
        .failed.foreach(e => logger.error(s"Error while closing the WoW connection: ${e.getMessage}"))
    })
    stopped.countDown()
  }

  // Exits from a new thread, so a network thread reporting a fatal error is not blocked by the shutdown hook
  // that sys.exit runs (the hook waits for those threads to stop).
  private[wowchat] def exitAsync(status: Int): Unit = {
    new Thread(new Runnable {
      override def run(): Unit = sys.exit(status)
    }, "wowchat-exit").start()
  }
}

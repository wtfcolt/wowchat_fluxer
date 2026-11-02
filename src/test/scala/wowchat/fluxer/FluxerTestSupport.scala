package wowchat.fluxer

import java.io.IOException

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{Logger => LogbackLogger}
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import wowchat.chat.{ChatChannel, ChatTransportListener, IncomingChatMessage}

import scala.collection.JavaConverters._
import scala.collection.mutable

object FluxerTestSupport {

  // Not a real credential: the format of a bot token (<application_id>.<secret>) with an obviously fake secret.
  val Token = "1500000000000000000.FAKE-TEST-SECRET-not-a-real-token"

  def json(text: String): JsonNode = Json.parse(text).getOrElse(throw new IllegalArgumentException(s"bad test json: $text"))

  def response(status: Int, body: String = "{}", headers: Map[String, String] = Map.empty): HttpResponse = {
    HttpResponse(status, headers.map { case (k, v) => k.toLowerCase -> v }, body)
  }

  // Captures log output from every logger while running f.
  def captureLogs[T](f: => T): (T, Seq[String]) = {
    val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[LogbackLogger]
    val appender = new ListAppender[ILoggingEvent]
    appender.start()
    root.addAppender(appender)
    try {
      val result = f
      (result, appender.list.asScala.map(e => e.getFormattedMessage + Option(e.getThrowableProxy).fold("")(t => " " + t.getMessage)).toList)
    } finally {
      root.detachAppender(appender)
    }
  }
}

// Answers requests with a scripted handler and records them.
class FakeHttp(handler: HttpRequest => HttpResponse) extends HttpTransport {

  val requests = mutable.ArrayBuffer.empty[HttpRequest]

  override def execute(request: HttpRequest): HttpResponse = synchronized {
    requests += request
    handler(request)
  }
}

object FakeHttp {

  // Returns the responses in order, then repeats the last one. A null entry throws an IOException.
  def sequence(responses: HttpResponse*): FakeHttp = {
    val queue = mutable.Queue(responses: _*)
    new FakeHttp(_ => {
      val next = if (queue.size > 1) queue.dequeue() else queue.head
      if (next == null) throw new IOException("connection reset") else next
    })
  }
}

class FakeGatewayConnection(val url: String, val token: String, val listener: GatewayListener) extends GatewayConnection {

  var started = false
  var stopped = false
  var self: Option[String] = Some("900")
  val sent = mutable.ArrayBuffer.empty[(String, String)]

  override def start(): Unit = started = true
  override def sendWhenReady(payload: String, description: String): Unit = synchronized { sent += payload -> description }
  override def selfUserId: Option[String] = self
  override def shutdown(): Unit = stopped = true

  def dispatch(event: String, data: String): Unit = listener.onDispatch(event, FluxerTestSupport.json(data))
}

class RecordingListener extends ChatTransportListener {

  val channelAnnouncements = mutable.ArrayBuffer.empty[Seq[ChatChannel]]
  val messages = mutable.ArrayBuffer.empty[IncomingChatMessage]
  var disconnects = 0
  val fatalErrors = mutable.ArrayBuffer.empty[String]

  override def onChannelsAvailable(channels: Seq[ChatChannel]): Unit = synchronized { channelAnnouncements += channels }
  override def onMessage(message: IncomingChatMessage): Unit = synchronized { messages += message }
  override def onDisconnected(): Unit = synchronized { disconnects += 1 }
  override def onFatalError(reason: String): Unit = synchronized { fatalErrors += reason }
}

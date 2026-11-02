package wowchat.fluxer

import com.fasterxml.jackson.databind.JsonNode
import com.typesafe.scalalogging.StrictLogging
import wowchat.fluxer.Json._

sealed trait GatewayAction
object GatewayAction {
  // description is a safe, token-free label for logs.
  case class Send(payload: String, description: String) extends GatewayAction
  case class StartHeartbeat(intervalMs: Long) extends GatewayAction
  case class Dispatch(event: String, data: JsonNode) extends GatewayAction
  // Close the current socket (if it is still open) and connect again after delayMs.
  case class Reconnect(delayMs: Long, reason: String) extends GatewayAction
  case class Fatal(reason: String) extends GatewayAction
}

// The Gateway protocol state of one session, without any I/O (https://docs.fluxer.com/gateway/overview/).
// It must be driven from a single thread.
class FluxerGatewaySession(token: String, clock: () => Long = () => System.currentTimeMillis) extends StrictLogging {

  import FluxerGatewaySession._
  import GatewayAction._

  private var sessionId: Option[String] = None
  private var seq: Option[Long] = None
  private var closedAt: Option[Long] = None
  private var helloReceived = false
  private var awaitingAck = false
  private var resuming = false
  private var ready = false
  private var consecutiveFailures = 0
  private var invalidSequenceCloses = 0
  private var _selfUserId: Option[String] = None

  // READY or RESUMED was received on the current socket, so commands such as Presence Update can be sent.
  def isReady: Boolean = ready

  def selfUserId: Option[String] = _selfUserId

  def lastSequence: Option[Long] = seq

  def currentSessionId: Option[String] = sessionId

  def onSocketOpened(): Seq[GatewayAction] = {
    helloReceived = false
    awaitingAck = false
    ready = false
    resuming = false
    Seq.empty
  }

  def onText(text: String): Seq[GatewayAction] = {
    Json.parse(text) match {
      case Some(payload) if payload.isObject && payload.int("op").isDefined =>
        handle(payload.int("op").get, payload)
      case _ =>
        logger.warn("Ignoring a malformed Fluxer Gateway payload.")
        Seq.empty
    }
  }

  private def handle(op: Int, payload: JsonNode): Seq[GatewayAction] = {
    op match {
      case Opcodes.Hello =>
        val interval = payload.obj("d").flatMap(_.long("heartbeat_interval")).filter(_ > 0).getOrElse({
          logger.warn(s"Fluxer Hello has no valid heartbeat_interval. Using ${DefaultHeartbeatIntervalMs}ms.")
          DefaultHeartbeatIntervalMs
        })
        helloReceived = true
        awaitingAck = false
        StartHeartbeat(interval) +: identifyOrResume()

      case Opcodes.HeartbeatAck =>
        awaitingAck = false
        Seq.empty

      case Opcodes.Heartbeat =>
        // The server asks for a heartbeat right away.
        Seq(Send(FluxerPayloads.heartbeat(seq), "Heartbeat (requested by server)"))

      case Opcodes.Dispatch =>
        handleDispatch(payload)

      case Opcodes.Reconnect =>
        logger.info("Fluxer Gateway asked us to reconnect.")
        closedAt = Some(clock())
        ready = false
        Seq(Reconnect(0, "server requested reconnect"))

      case Opcodes.InvalidSession =>
        // The session cannot be resumed. The socket stays open and accepts Identify.
        logger.info("Fluxer Gateway session is no longer valid. Starting a new session.")
        clearSession()
        Seq(identify())

      case Opcodes.GatewayError =>
        // Only sent for refused voice joins, which this bridge never makes.
        Seq.empty

      case other =>
        logger.debug(s"Ignoring Fluxer Gateway opcode $other.")
        Seq.empty
    }
  }

  private def handleDispatch(payload: JsonNode): Seq[GatewayAction] = {
    val event = payload.str("t")
    val data = Option(payload.get("d")).getOrElse(Json.obj())
    // Only events that are handed on count as processed: the Gateway drops buffered events up to the sequence
    // sent in a heartbeat.
    if (event.isDefined) {
      payload.long("s").foreach(s => seq = Some(s))
    }

    event match {
      case None =>
        logger.warn("Ignoring a Fluxer Dispatch without an event name.")
        Seq.empty
      case Some("READY") =>
        data.str("session_id") match {
          case Some(id) =>
            sessionId = Some(id)
            _selfUserId = data.obj("user").flatMap(_.id("id"))
            onSessionStarted()
            Seq(Dispatch("READY", data))
          case None =>
            logger.warn("Fluxer READY has no session_id. Sessions from this connection cannot be resumed.")
            onSessionStarted()
            Seq(Dispatch("READY", data))
        }
      case Some("RESUMED") =>
        onSessionStarted()
        Seq(Dispatch("RESUMED", data))
      case Some(name) =>
        Seq(Dispatch(name, data))
    }
  }

  private def onSessionStarted(): Unit = {
    ready = true
    resuming = false
    consecutiveFailures = 0
    invalidSequenceCloses = 0
  }

  def onHeartbeatTimer(): Seq[GatewayAction] = {
    if (!helloReceived) {
      Seq.empty
    } else if (awaitingAck) {
      // The previous heartbeat was never acknowledged: the connection is dead even if the socket looks open.
      closedAt = Some(clock())
      ready = false
      Seq(Reconnect(0, "no heartbeat acknowledgement"))
    } else {
      awaitingAck = true
      Seq(Send(FluxerPayloads.heartbeat(seq), "Heartbeat"))
    }
  }

  // The socket closed. code is the close frame code, or 1006 when there was none.
  def onSocketClosed(code: Int, reason: String): Seq[GatewayAction] = {
    closedAt = Some(clock())
    ready = false
    helloReceived = false
    val wasResuming = resuming
    resuming = false

    if (wasResuming && code == CloseCodes.AuthenticationFailed) {
      // "The token does not own the session": start a new session. If the token itself is bad, the next
      // Identify closes with 4004 again, which is fatal.
      logger.warn("Fluxer refused to resume the session (4004). Starting a new session.")
      clearSession()
      return Seq(Reconnect(nextBackoff(), "resume refused"))
    }

    if (wasResuming && code == CloseCodes.InvalidSequence) {
      invalidSequenceCloses += 1
      if (invalidSequenceCloses >= 2) {
        logger.warn("Fluxer refused the resume sequence twice. Starting a new session.")
        clearSession()
      }
      return Seq(Reconnect(nextBackoff(), "invalid sequence"))
    }

    CloseCodes.handling(code) match {
      case CloseHandling.Fatal(message) => Seq(Fatal(message))
      case CloseHandling.Reconnect => Seq(Reconnect(nextBackoff(), s"closed with $code (${CloseCodes.describe(code)})"))
    }
  }

  def onConnectFailed(): Seq[GatewayAction] = {
    Seq(Reconnect(nextBackoff(), "connection failed"))
  }

  def canResume: Boolean = {
    sessionId.isDefined && seq.isDefined && closedAt.exists(at => clock() - at < ResumeWindowMs - ResumeSafetyMarginMs)
  }

  private def identifyOrResume(): Seq[GatewayAction] = {
    if (canResume) {
      resuming = true
      Seq(Send(FluxerPayloads.resume(token, sessionId.get, seq.get), "Resume"))
    } else {
      if (sessionId.isDefined) {
        logger.info("The previous Fluxer session can no longer be resumed. Starting a new session.")
      }
      clearSession()
      Seq(identify())
    }
  }

  private def identify(): GatewayAction = {
    resuming = false
    Send(FluxerPayloads.identify(token), "Identify")
  }

  private def clearSession(): Unit = {
    sessionId = None
    seq = None
  }

  private def nextBackoff(): Long = {
    consecutiveFailures += 1
    math.min(MaxBackoffMs, InitialBackoffMs << math.min(consecutiveFailures - 1, 10))
  }
}

object FluxerGatewaySession {

  val DefaultHeartbeatIntervalMs = 41250L

  // "Opcode 6 resumes a session on a new socket within 60,000 ms after the old one closes."
  val ResumeWindowMs = 60000L
  // The window is measured by the server from the close. Leave time for connecting and the Hello round trip.
  val ResumeSafetyMarginMs = 5000L

  val InitialBackoffMs = 1000L
  val MaxBackoffMs = 60000L
}

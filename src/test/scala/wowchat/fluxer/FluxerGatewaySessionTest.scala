package wowchat.fluxer

import org.junit.Assert._
import org.junit.{Before, Test}
import wowchat.fluxer.FluxerTestSupport._
import wowchat.fluxer.GatewayAction._
import wowchat.fluxer.Json._

class FluxerGatewaySessionTest {

  private var now = 1000000L
  private var session: FluxerGatewaySession = _

  @Before
  def setUp(): Unit = {
    now = 1000000L
    session = new FluxerGatewaySession(Token, () => now)
  }

  private val hello = """{"op":10,"d":{"heartbeat_interval":41250}}"""
  private val ready = """{"op":0,"t":"READY","s":1,"d":{"session_id":"sess-1","user":{"id":"900","username":"bot"},"guilds":[]}}"""

  private def sends(actions: Seq[GatewayAction]): Seq[String] = actions.collect { case Send(_, description) => description }

  private def sentPayload(actions: Seq[GatewayAction], description: String) =
    actions.collectFirst { case Send(payload, d) if d == description => json(payload) }.get

  private def connectAndReady(): Unit = {
    session.onSocketOpened()
    session.onText(hello)
    session.onText(ready)
  }

  @Test
  def helloStartsHeartbeatAndIdentifies(): Unit = {
    session.onSocketOpened()
    val actions = session.onText(hello)
    assertEquals(StartHeartbeat(41250), actions.head)
    assertEquals(Seq("Identify"), sends(actions))
  }

  @Test
  def readyStoresSessionAndSelfUser(): Unit = {
    connectAndReady()
    assertTrue(session.isReady)
    assertEquals(Some("sess-1"), session.currentSessionId)
    assertEquals(Some("900"), session.selfUserId)
    assertEquals(Some(1L), session.lastSequence)
  }

  @Test
  def dispatchesAreForwardedAndSequenceTracked(): Unit = {
    connectAndReady()
    val actions = session.onText("""{"op":0,"t":"MESSAGE_CREATE","s":5,"d":{"id":"1"}}""")
    assertEquals("MESSAGE_CREATE", actions.collectFirst { case Dispatch(event, _) => event }.get)
    assertEquals(Some(5L), session.lastSequence)
  }

  @Test
  def heartbeatSendsLastSequenceAndDetectsMissingAck(): Unit = {
    connectAndReady()
    val first = session.onHeartbeatTimer()
    assertEquals(Some(1L), sentPayload(first, "Heartbeat").long("d"))
    session.onText("""{"op":11}""")
    assertEquals(Seq("Heartbeat"), sends(session.onHeartbeatTimer()))
    // no ACK for the second heartbeat
    assertTrue(session.onHeartbeatTimer().head.isInstanceOf[Reconnect])
  }

  @Test
  def heartbeatBeforeHelloDoesNothing(): Unit = {
    session.onSocketOpened()
    assertTrue(session.onHeartbeatTimer().isEmpty)
  }

  @Test
  def heartbeatBeforeFirstDispatchIsNull(): Unit = {
    session.onSocketOpened()
    session.onText(hello)
    assertTrue(sentPayload(session.onHeartbeatTimer(), "Heartbeat").get("d").isNull)
  }

  @Test
  def serverRequestedHeartbeatIsAnsweredImmediately(): Unit = {
    connectAndReady()
    val actions = session.onText("""{"op":1,"d":null}""")
    assertEquals(Some(1L), sentPayload(actions, "Heartbeat (requested by server)").long("d"))
  }

  @Test
  def reconnectAfterCloseResumesWithinWindow(): Unit = {
    connectAndReady()
    session.onText("""{"op":0,"t":"MESSAGE_CREATE","s":7,"d":{}}""")
    val closed = session.onSocketClosed(4000, "Unknown error")
    assertTrue(closed.head.isInstanceOf[Reconnect])
    assertFalse(session.isReady)

    now += 10000
    session.onSocketOpened()
    val actions = session.onText(hello)
    val resume = sentPayload(actions, "Resume")
    assertEquals(Some("sess-1"), resume.obj("d").flatMap(_.str("session_id")))
    assertEquals(Some(7L), resume.obj("d").flatMap(_.long("seq")))
    assertEquals(Some(Token), resume.obj("d").flatMap(_.str("token")))

    session.onText("""{"op":0,"t":"RESUMED","s":7,"d":{}}""")
    assertTrue(session.isReady)
  }

  @Test
  def reconnectAfterResumeWindowIdentifies(): Unit = {
    connectAndReady()
    session.onSocketClosed(1006, "")
    now += FluxerGatewaySession.ResumeWindowMs
    session.onSocketOpened()
    assertEquals(Seq("Identify"), sends(session.onText(hello)))
    assertEquals(None, session.currentSessionId)
  }

  @Test
  def resumeWindowKeepsSafetyMargin(): Unit = {
    connectAndReady()
    session.onSocketClosed(1006, "")
    now += FluxerGatewaySession.ResumeWindowMs - FluxerGatewaySession.ResumeSafetyMarginMs - 1
    assertTrue(session.canResume)
    now += 1
    assertFalse(session.canResume)
  }

  @Test
  def serverReconnectRequestKeepsSessionForResume(): Unit = {
    connectAndReady()
    val actions = session.onText("""{"op":7}""")
    assertEquals(Reconnect(0, "server requested reconnect"), actions.head)
    session.onSocketOpened()
    assertEquals(Seq("Resume"), sends(session.onText(hello)))
  }

  @Test
  def invalidSessionIdentifiesOnSameSocket(): Unit = {
    connectAndReady()
    session.onSocketClosed(4000, "")
    session.onSocketOpened()
    session.onText(hello) // sends Resume
    val actions = session.onText("""{"op":9,"d":false}""")
    assertEquals(Seq("Identify"), sends(actions))
    assertFalse(actions.exists(_.isInstanceOf[Reconnect]))
    assertEquals(None, session.currentSessionId)
  }

  @Test
  def authenticationFailureIsFatal(): Unit = {
    session.onSocketOpened()
    session.onText(hello)
    val actions = session.onSocketClosed(4004, "Invalid token")
    assertTrue(actions.head.isInstanceOf[Fatal])
  }

  @Test
  def authenticationFailureDuringResumeStartsNewSession(): Unit = {
    connectAndReady()
    session.onSocketClosed(1006, "")
    session.onSocketOpened()
    session.onText(hello) // Resume
    val actions = session.onSocketClosed(4004, "Invalid token")
    assertTrue(actions.head.isInstanceOf[Reconnect])
    session.onSocketOpened()
    assertEquals(Seq("Identify"), sends(session.onText(hello)))
  }

  @Test
  def repeatedInvalidSequenceOnResumeStartsNewSession(): Unit = {
    connectAndReady()
    session.onSocketClosed(1006, "")
    session.onSocketOpened()
    session.onText(hello)
    session.onSocketClosed(4007, "Invalid sequence")
    session.onSocketOpened()
    assertEquals(Seq("Resume"), sends(session.onText(hello))) // first 4007: retry the resume
    session.onSocketClosed(4007, "Invalid sequence")
    session.onSocketOpened()
    assertEquals(Seq("Identify"), sends(session.onText(hello)))
  }

  @Test
  def otherFatalCloseCodes(): Unit = {
    Seq(4010, 4011, 4012).foreach(code => {
      val s = new FluxerGatewaySession(Token, () => now)
      s.onSocketOpened()
      assertTrue(s"$code", s.onSocketClosed(code, "").head.isInstanceOf[Fatal])
    })
  }

  @Test
  def backoffGrowsAndResetsAfterReady(): Unit = {
    def delay(actions: Seq[GatewayAction]) = actions.collectFirst { case Reconnect(d, _) => d }.get
    assertEquals(1000L, delay(session.onConnectFailed()))
    assertEquals(2000L, delay(session.onConnectFailed()))
    assertEquals(4000L, delay(session.onSocketClosed(4008, "Rate limited")))
    (1 to 10).foreach(_ => session.onConnectFailed())
    assertEquals(FluxerGatewaySession.MaxBackoffMs, delay(session.onConnectFailed()))
    connectAndReady()
    assertEquals(1000L, delay(session.onSocketClosed(1006, "")))
  }

  @Test
  def malformedPayloadsAreIgnored(): Unit = {
    connectAndReady()
    Seq("not json", "[1,2,3]", "{}", """{"op":"10"}""", """{"op":0,"s":2}""", """{"op":99,"d":{}}""", """{"op":12,"d":{"code":"X"}}""", "")
      .foreach(text => assertEquals(text, Seq.empty, session.onText(text)))
    assertTrue(session.isReady)
    // the event-less Dispatch with s=2 was not processed, so it must not be acknowledged
    assertEquals(Some(1L), session.lastSequence)
  }

  @Test
  def helloWithoutValidIntervalUsesDefault(): Unit = {
    session.onSocketOpened()
    assertEquals(StartHeartbeat(FluxerGatewaySession.DefaultHeartbeatIntervalMs), session.onText("""{"op":10,"d":{"heartbeat_interval":-5}}""").head)
  }

  @Test
  def readyWithoutSessionIdCannotResume(): Unit = {
    session.onSocketOpened()
    session.onText(hello)
    session.onText("""{"op":0,"t":"READY","s":1,"d":{"user":{"id":"900"}}}""")
    assertTrue(session.isReady)
    session.onSocketClosed(1006, "")
    assertFalse(session.canResume)
  }
}

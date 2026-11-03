package wowchat.fluxer

import org.junit.Assert._
import org.junit.Test
import wowchat.fluxer.FluxerTestSupport._
import wowchat.fluxer.Json._

import scala.collection.JavaConverters._

class FluxerPayloadsTest {

  @Test
  def identifySendsRawTokenAndDocumentedFields(): Unit = {
    val payload = json(FluxerPayloads.identify(Token))
    assertEquals(Some(2), payload.int("op"))
    val d = payload.obj("d").get
    // Raw token, no "Bot " prefix
    assertEquals(Some(Token), d.str("token"))
    val properties = d.obj("properties").get
    assertTrue(properties.str("os").exists(_.nonEmpty))
    assertEquals(Some("wowchat"), properties.str("browser"))
    assertEquals(Some("wowchat"), properties.str("device"))
    assertEquals(Seq("TYPING_START", "MESSAGE_REACTION_ADD", "MESSAGE_REACTION_REMOVE"), d.arr("ignored_events").map(_.asText))
    assertEquals(Some("online"), d.obj("presence").flatMap(_.str("status")))
    // Fluxer has no gateway intents
    assertFalse(d.has("intents"))
    assertFalse(d.has("shard"))
  }

  @Test
  def resume(): Unit = {
    val payload = json(FluxerPayloads.resume(Token, "abc", 42))
    assertEquals(Some(6), payload.int("op"))
    assertEquals(Some(Token), payload.obj("d").flatMap(_.str("token")))
    assertEquals(Some("abc"), payload.obj("d").flatMap(_.str("session_id")))
    assertEquals(Some(42L), payload.obj("d").flatMap(_.long("seq")))
  }

  @Test
  def heartbeatCarriesSequenceOrNull(): Unit = {
    assertEquals("""{"op":1,"d":null}""", FluxerPayloads.heartbeat(None))
    assertEquals("""{"op":1,"d":17}""", FluxerPayloads.heartbeat(Some(17)))
  }

  @Test
  def presenceSetsCustomStatusText(): Unit = {
    val d = json(FluxerPayloads.presence(Some("  Test Realm \n"))).obj("d").get
    assertEquals(Some("online"), d.str("status"))
    assertEquals(Some("Test Realm"), d.obj("custom_status").flatMap(_.str("text")))
  }

  @Test
  def emptyPresenceClearsCustomStatus(): Unit = {
    val payload = json(FluxerPayloads.presence(Some("   ")))
    assertTrue(payload.get("d").get("custom_status").isNull)
  }

  @Test
  def customStatusIsLimitedTo128CodePoints(): Unit = {
    val emoji = "😀" // one code point, two chars
    val text = emoji * 130
    val result = FluxerPayloads.customStatusText(Some(text)).get
    assertEquals(128, result.codePointCount(0, result.length))
    assertEquals(emoji * 128, result)
  }

  @Test
  def payloadsAreWellUnderTheGatewayLimit(): Unit = {
    assertTrue(FluxerPayloads.byteLength(FluxerPayloads.identify(Token)) < FluxerPayloads.MaxPayloadBytes)
    assertTrue(FluxerPayloads.byteLength(FluxerPayloads.presence(Some("x" * 1000))) < FluxerPayloads.MaxPayloadBytes)
  }

  @Test
  def closeCodeHandling(): Unit = {
    Seq(4004, 4010, 4011, 4012).foreach(code => assertTrue(s"$code", CloseCodes.handling(code).isInstanceOf[CloseHandling.Fatal]))
    Seq(1000, 1001, 1006, 4000, 4001, 4002, 4003, 4005, 4007, 4008, 4009, 4999).foreach(code =>
      assertEquals(s"$code", CloseHandling.Reconnect, CloseCodes.handling(code)))
  }

  @Test
  def gatewayConnectionUriAddsParametersAndKeepsQuery(): Unit = {
    assertEquals("wss://gateway.invalid/?v=1&encoding=json", FluxerGateway.connectionUri("wss://gateway.invalid").toString)
    assertEquals("wss://gateway.invalid:8443/ws?region=eu&v=1&encoding=json",
      FluxerGateway.connectionUri("wss://gateway.invalid:8443/ws?region=eu").toString)
  }

  @Test
  def jsonIdsAcceptStringsAndIntegersOnly(): Unit = {
    val node = json("""{"a":"123","b":456,"c":1.5,"d":"12x","e":null,"f":-3}""")
    assertEquals(Some("123"), node.id("a"))
    assertEquals(Some("456"), node.id("b"))
    assertEquals(None, node.id("c"))
    assertEquals(None, node.id("d"))
    assertEquals(None, node.id("e"))
    assertEquals(None, node.id("f"))
    assertEquals(None, node.id("missing"))
    assertTrue(json("[1,2]").arr("x").isEmpty)
    assertEquals(Seq("1", "2"), json("""{"x":[1,2]}""").arr("x").map(_.asText))
    assertTrue(json("""{"x":[1,2]}""").get("x").elements.asScala.nonEmpty)
  }
}

package wowchat.fluxer

import java.io.IOException

import org.junit.Assert._
import org.junit.Test
import wowchat.chat.ChatChannel
import wowchat.fluxer.FluxerTestSupport._

// The bot token must never appear in logs or error messages.
class TokenRedactionTest {

  private val Secret = Token.substring(Token.indexOf('.') + 1)

  private def assertClean(lines: Seq[String]): Unit = {
    lines.foreach(line => {
      assertFalse(s"token in log line: $line", line.contains(Token))
      assertFalse(s"token secret in log line: $line", line.contains(Secret))
    })
  }

  @Test
  def redactRemovesTokenAndSecret(): Unit = {
    assertEquals("a [REDACTED] b", Redaction.redact(s"a $Token b", Token))
    assertEquals("secret [REDACTED]", Redaction.redact(s"secret $Secret", Token))
    assertEquals("nothing here", Redaction.redact("nothing here", Token))
    assertEquals(null, Redaction.redact(null, Token))
  }

  @Test
  def configNeverPrintsToken(): Unit = {
    val text = FluxerConfig(Token, Some("https://chat.example.invalid")).toString
    assertFalse(text.contains(Token))
    assertFalse(text.contains(Secret))
  }

  @Test
  def restFailuresDoNotLogToken(): Unit = {
    val (_, lines) = captureLogs {
      // the server echoes the token back in error messages and the network error mentions it
      val http = new FakeHttp(request => {
        if (request.url.contains("/channels/1/")) response(401, s"""{"code":"UNAUTHORIZED","message":"bad $Token"}""")
        else if (request.url.contains("/channels/2/")) throw new IOException(s"reset while sending $Token")
        else response(403, s"""{"code":"MISSING_PERMISSIONS","message":"$Secret"}""")
      })
      val client = new FluxerRestClient(http, "https://api.example.invalid", Token, _ => ())
      Seq("1", "2", "3").foreach(channel => client.createMessage(channel, "hi"))
      client.createMessageAsync("3", "async")
      client.shutdown(5000)
    }
    assertTrue(lines.exists(_.contains("401")))
    assertTrue(lines.exists(_.contains("MISSING_PERMISSIONS")))
    assertClean(lines)
  }

  @Test
  def gatewayAndTransportDoNotLogToken(): Unit = {
    val (_, lines) = captureLogs {
      val session = new FluxerGatewaySession(Token)
      session.onSocketOpened()
      session.onText("""{"op":10,"d":{"heartbeat_interval":1000}}""")
      session.onText("""{"op":0,"t":"READY","s":1,"d":{"session_id":"s","user":{"id":"900"}}}""")
      session.onSocketClosed(4000, s"reason with $Token")
      session.onSocketOpened()
      session.onText("""{"op":10,"d":{"heartbeat_interval":1000}}""")
      session.onSocketClosed(4004, "Invalid token")
      session.onSocketOpened()
      session.onText("""{"op":10,"d":{"heartbeat_interval":1000}}""")
      session.onSocketClosed(4004, "Invalid token")

      val http = new FakeHttp(_ => throw new IOException(s"cannot reach instance ($Token)"))
      val listener = new RecordingListener
      val transport = new FluxerTransport(FluxerConfig(Token, Some("https://chat.example.invalid")), http,
        (url, token, l) => new FakeGatewayConnection(url, token, l), _ => ())
      transport.start(listener)
      transport.postMessage(ChatChannel("1", "x"), "hi")
      transport.shutdown()
      assertClean(listener.fatalErrors)
    }
    assertClean(lines)
  }
}

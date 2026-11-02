package wowchat.fluxer

import org.junit.Assert._
import org.junit.Test
import wowchat.fluxer.FluxerTestSupport._
import wowchat.fluxer.Json._

import scala.collection.mutable

class FluxerRestClientTest {

  private var now = 0L
  private val sleeps = mutable.ArrayBuffer.empty[Long]

  private def client(http: HttpTransport) = new FluxerRestClient(http, "https://api.example.invalid/", Token,
    sleeper = ms => { sleeps += ms; now += ms }, clock = () => now)

  private val ok = response(200, """{"id":"1","channel_id":"300"}""")

  @Test
  def createMessageRequest(): Unit = {
    val http = FakeHttp.sequence(ok)
    assertTrue(client(http).createMessage("300", "hello").isRight)

    val request = http.requests.head
    assertEquals("POST", request.method)
    assertEquals("https://api.example.invalid/v1/channels/300/messages", request.url)
    assertEquals(Some(s"Bot $Token"), request.header("Authorization"))
    assertEquals(Some("application/json"), request.header("Content-Type"))
    assertFalse(request.followRedirects)

    val body = json(request.body.get)
    assertEquals(Some("hello"), body.str("content"))
    assertTrue(body.str("nonce").exists(n => n.nonEmpty && n.length <= 32))
    // @everyone and @here can never notify
    assertEquals(Seq("users", "roles"), body.obj("allowed_mentions").get.arr("parse").map(_.asText))
    assertFalse(body.obj("allowed_mentions").get.has("users"))
  }

  @Test
  def requestStringNeverShowsHeaders(): Unit = {
    val http = FakeHttp.sequence(ok)
    client(http).createMessage("300", "hello")
    assertFalse(http.requests.head.toString.contains(Token))
  }

  @Test
  def rateLimitUsesBodyRetryAfterAndKeepsNonce(): Unit = {
    val limited = response(429, """{"code":"RATE_LIMITED","message":"slow down","global":false,"retry_after":0.428}""", Map("Retry-After" -> "1"))
    val http = FakeHttp.sequence(limited, ok)
    assertTrue(client(http).createMessage("300", "hello").isRight)
    assertEquals(Seq(428L), sleeps)
    assertEquals(2, http.requests.size)
    // the retry reuses the nonce so the message is never posted twice
    assertEquals(json(http.requests(0).body.get).str("nonce"), json(http.requests(1).body.get).str("nonce"))
  }

  @Test
  def rateLimitFallsBackToRetryAfterHeader(): Unit = {
    val http = FakeHttp.sequence(response(429, "", Map("Retry-After" -> "2")), ok)
    assertTrue(client(http).createMessage("300", "hello").isRight)
    assertEquals(Seq(2000L), sleeps)
  }

  @Test
  def globalRateLimitPausesOtherRoutes(): Unit = {
    val http = FakeHttp.sequence(response(429, """{"code":"RATE_LIMITED","message":"x","global":true,"retry_after":3}"""), ok, ok)
    val c = client(http)
    assertTrue(c.createMessage("300", "a").isRight)
    assertEquals(Seq(3000L), sleeps)
    // the global block has passed (the clock advanced during the wait), so another route does not wait again
    assertTrue(c.createMessage("301", "b").isRight)
    assertEquals(Seq(3000L), sleeps)
  }

  @Test
  def globalRateLimitBlocksAllRoutesUntilItExpires(): Unit = {
    val state = new RateLimitState(() => now)
    state.blockGlobal(5000)
    assertEquals(5000L, state.waitTimeMs("a"))
    assertEquals(5000L, state.waitTimeMs("b"))
    now += 4000
    assertEquals(1000L, state.waitTimeMs("b"))
  }

  @Test
  def emptyBucketWaitsForReset(): Unit = {
    val drained = response(200, "{}", Map("X-RateLimit-Remaining" -> "0", "X-RateLimit-Reset-After" -> "1.5"))
    val http = FakeHttp.sequence(drained, ok)
    val c = client(http)
    c.createMessage("300", "a")
    c.createMessage("300", "b")
    assertEquals(Seq(1500L), sleeps)
    // a different channel is a different bucket
    sleeps.clear()
    c.createMessage("301", "c")
    assertTrue(sleeps.isEmpty)
  }

  @Test
  def serverErrorsAreRetriedThenGivenUp(): Unit = {
    val http = FakeHttp.sequence(response(502))
    val result = client(http).createMessage("300", "hello")
    assertEquals(Some(502), result.left.get.status)
    assertEquals(RestRetryPolicy.MaxServerErrorAttempts, http.requests.size)
    assertEquals(Seq(1000L, 2000L), sleeps)
  }

  @Test
  def serverErrorThenSuccess(): Unit = {
    val http = FakeHttp.sequence(response(503, "", Map("Retry-After" -> "4")), ok)
    assertTrue(client(http).createMessage("300", "hello").isRight)
    assertEquals(Seq(4000L), sleeps)
  }

  @Test
  def networkFailuresAreRetried(): Unit = {
    val http = FakeHttp.sequence(null, ok)
    assertTrue(client(http).createMessage("300", "hello").isRight)
    assertEquals(2, http.requests.size)

    val failing = FakeHttp.sequence(null)
    val result = client(failing).createMessage("300", "hello")
    assertEquals(None, result.left.get.status)
    assertEquals(RestRetryPolicy.MaxNetworkAttempts, failing.requests.size)
  }

  @Test
  def clientErrorsAreNotRetried(): Unit = {
    Seq(400, 401, 403, 404).foreach(status => {
      val http = FakeHttp.sequence(response(status, s"""{"code":"CODE_$status","message":"nope"}""", Map("X-Request-ID" -> "req-1")))
      val failure = client(http).createMessage("300", "hello").left.get
      assertEquals(Some(status), failure.status)
      assertEquals(Some(s"CODE_$status"), failure.code)
      assertEquals(Some("req-1"), failure.requestId)
      assertEquals(1, http.requests.size)
    })
  }

  @Test
  def rateLimitRetriesAreBounded(): Unit = {
    val http = FakeHttp.sequence(response(429, """{"retry_after":0.01}"""))
    assertEquals(Some(429), client(http).createMessage("300", "hello").left.get.status)
    assertEquals(RestRetryPolicy.MaxRateLimitRetries + 1, http.requests.size)
  }

  @Test
  def lockedResourceWaitsForRetryAfter(): Unit = {
    val http = FakeHttp.sequence(response(423, "", Map("Retry-After" -> "2")), ok)
    assertTrue(client(http).createMessage("300", "hello").isRight)
    assertEquals(Seq(2000L), sleeps)
  }

  @Test
  def listGuildMembersRequest(): Unit = {
    val http = FakeHttp.sequence(response(200, """[{"user":{"id":"1","username":"a"}}]"""))
    val members = client(http).listGuildMembers("10", Some("5"), 1000).right.get
    assertEquals(1, members.size)
    assertEquals("GET", http.requests.head.method)
    assertEquals("https://api.example.invalid/v1/guilds/10/members?limit=1000&after=5", http.requests.head.url)
    assertEquals(None, http.requests.head.body)
  }

  @Test
  def unexpectedMemberListResponse(): Unit = {
    assertTrue(client(FakeHttp.sequence(response(200, """{"not":"an array"}"""))).listGuildMembers("10", None, 1000).isLeft)
  }

  @Test
  def errorMessagesAreRedacted(): Unit = {
    val http = FakeHttp.sequence(response(400, s"""{"code":"X","message":"bad token $Token"}"""))
    val failure = client(http).createMessage("300", "hello").left.get
    assertFalse(failure.toString.contains(Token))
    assertFalse(failure.toString.contains("FAKE-TEST-SECRET"))
  }
}

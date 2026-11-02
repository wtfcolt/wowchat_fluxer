package wowchat.fluxer

import org.junit.Assert._
import org.junit.Test
import wowchat.fluxer.FluxerTestSupport._

class FluxerDiscoveryTest {

  private val document =
    """{"api_code_version":1,"endpoints":{"api":"https://api.example.invalid","api_client":"https://api.example.invalid",
      |"api_public":"https://public-api.example.invalid","gateway":"wss://gateway.example.invalid","media":"https://media.example.invalid"},
      |"features":{"self_hosted":true}}""".stripMargin

  private val noSleep: Long => Unit = _ => ()

  private def config(instance: Option[String] = Some("https://chat.example.invalid/"), api: Option[String] = None, gateway: Option[String] = None) =
    FluxerConfig(Token, instance, api, gateway)

  private def assertConfigError(f: => Any, expected: String): Unit = {
    try {
      f
      fail("expected a configuration error")
    } catch {
      case e: FluxerConfigurationException => assertTrue(e.getMessage, e.getMessage.contains(expected))
    }
  }

  @Test
  def parsesPublicApiAndGateway(): Unit = {
    assertEquals(Right(FluxerEndpoints("https://public-api.example.invalid", "wss://gateway.example.invalid")), FluxerDiscovery.parse(document))
  }

  @Test
  def rejectsIncompleteOrInvalidDocuments(): Unit = {
    assertTrue(FluxerDiscovery.parse("not json").isLeft)
    assertTrue(FluxerDiscovery.parse("[]").isLeft)
    assertTrue(FluxerDiscovery.parse("""{"endpoints":{"gateway":"wss://g.invalid"}}""").left.get.contains("api_public"))
    assertTrue(FluxerDiscovery.parse("""{"endpoints":{"api_public":"https://a.invalid"}}""").left.get.contains("gateway"))
    assertTrue(FluxerDiscovery.parse("""{"endpoints":{"api_public":"https://a.invalid","gateway":"https://g.invalid"}}""").isLeft)
    assertTrue(FluxerDiscovery.parse("""{"endpoints":{"api_public":"ftp://a.invalid","gateway":"wss://g.invalid"}}""").isLeft)
  }

  @Test
  def wellKnownUrlIsOnTheOrigin(): Unit = {
    assertEquals("https://chat.example.invalid/.well-known/fluxer", FluxerDiscovery.wellKnownUrl("https://chat.example.invalid/"))
    assertEquals("https://chat.example.invalid/.well-known/fluxer", FluxerDiscovery.wellKnownUrl(" https://chat.example.invalid "))
  }

  @Test
  def discoversWithoutCredentials(): Unit = {
    val http = FakeHttp.sequence(response(200, document))
    assertEquals(FluxerEndpoints("https://public-api.example.invalid", "wss://gateway.example.invalid"),
      FluxerDiscovery.resolve(config(), http, noSleep))
    assertEquals("https://chat.example.invalid/.well-known/fluxer", http.requests.head.url)
    assertEquals(None, http.requests.head.header("Authorization"))
  }

  @Test
  def overridesSkipDiscovery(): Unit = {
    val http = FakeHttp.sequence(response(500))
    assertEquals(FluxerEndpoints("https://api.override.invalid", "wss://gw.override.invalid"),
      FluxerDiscovery.resolve(config(None, Some("https://api.override.invalid"), Some("wss://gw.override.invalid")), http, noSleep))
    assertTrue(http.requests.isEmpty)
  }

  @Test
  def singleOverrideReplacesOneDiscoveredEndpoint(): Unit = {
    val http = FakeHttp.sequence(response(200, document))
    assertEquals(FluxerEndpoints("https://public-api.example.invalid", "wss://gw.override.invalid"),
      FluxerDiscovery.resolve(config(gateway = Some("wss://gw.override.invalid")), http, noSleep))
  }

  @Test
  def retriesServerErrorsAndNetworkFailures(): Unit = {
    val http = FakeHttp.sequence(response(503), null, response(200, document))
    assertEquals("wss://gateway.example.invalid", FluxerDiscovery.resolve(config(), http, noSleep).gatewayUrl)
    assertEquals(3, http.requests.size)
  }

  @Test
  def givesUpAfterRepeatedFailures(): Unit = {
    assertConfigError(FluxerDiscovery.resolve(config(), FakeHttp.sequence(response(503)), noSleep), "failed: HTTP 503")
  }

  @Test
  def notFoundIsAConfigurationError(): Unit = {
    assertConfigError(FluxerDiscovery.resolve(config(), FakeHttp.sequence(response(404)), noSleep), "Check fluxer.instance_url")
  }

  @Test
  def invalidDocumentIsAConfigurationError(): Unit = {
    assertConfigError(FluxerDiscovery.resolve(config(), FakeHttp.sequence(response(200, "<html>")), noSleep), "Invalid Fluxer discovery document")
  }

  @Test
  def missingOrInvalidUrlsAreConfigurationErrors(): Unit = {
    val http = FakeHttp.sequence(response(200, document))
    assertConfigError(FluxerDiscovery.resolve(config(None), http, noSleep), "fluxer.instance_url is required")
    assertConfigError(FluxerDiscovery.resolve(config(Some("chat.example.invalid")), http, noSleep), "fluxer.instance_url must be")
    assertConfigError(FluxerDiscovery.resolve(config(gateway = Some("https://g.invalid")), http, noSleep), "fluxer.gateway_url must be")
    assertConfigError(FluxerDiscovery.resolve(config(api = Some("wss://a.invalid")), http, noSleep), "fluxer.api_url must be")
  }
}

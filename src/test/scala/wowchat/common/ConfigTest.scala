package wowchat.common

import java.io.File

import com.typesafe.config.{Config, ConfigFactory}
import org.junit.Assert._
import org.junit.Test
import wowchat.TestSupport

class ConfigTest {

  // Make sure the expansion is initialized from test.conf before any config is parsed here.
  TestSupport.baseConfig

  private val FakeToken = "1500000000000000000.FAKE-TEST-SECRET-not-a-real-token"

  // The sample reads credentials from environment variables; supply dummies.
  private val dummyWow = ConfigFactory.parseString(
    s"""wow { account=a, password=b, character=c }
       |fluxer { token="$FakeToken", instance_url="https://fluxer.example.invalid" }""".stripMargin)

  private def parse(hocon: String): WowChatConfig = {
    WowChatConfig.fromConfig(ConfigFactory.parseString(hocon).withFallback(dummyWow).resolve)
  }

  private val minimal =
    """wow { version=1.12.1, realmlist=realm.invalid, realm=r }
      |chat { channels=[ { direction=both, wow { type=Guild }, fluxer { channel=g, format="%message" } } ] }
      |""".stripMargin

  // Parses minimal with this fluxer section instead of the dummy one.
  private def parseFluxer(fluxer: String): WowChatConfig = {
    WowChatConfig.fromConfig(ConfigFactory.parseString(minimal + fluxer)
      .withFallback(ConfigFactory.parseString("wow { account=a, password=b, character=c }")).resolve)
  }

  private def assertRejected(hocon: String, expectedMessagePart: String): Unit = {
    try {
      parse(hocon)
      fail("expected configuration to be rejected")
    } catch {
      case e: RuntimeException => assertTrue(e.getMessage, e.getMessage.contains(expectedMessagePart))
    }
  }

  @Test
  def sampleConfigurationParses(): Unit = {
    val sample: Config = ConfigFactory.parseFile(new File("src/main/resources/wowchat.conf"))
    val config = WowChatConfig.fromConfig(sample.withFallback(dummyWow).resolve)

    assertEquals(10, config.channels.size)
    assertEquals(ChatDirection.both, config.channels.head.chatDirection)
    assertEquals("guild-chat", config.channels.head.fluxer.channel)
    assertEquals(6, config.channels.count(_.chatDirection == ChatDirection.wow_to_fluxer))
    assertTrue(config.chat.enableDotCommands)
    assertTrue(config.chat.dotCommandsWhitelist.isEmpty)
    assertEquals(Some(""), config.chat.itemDatabase)
  }

  @Test
  def chatSettingsDefaults(): Unit = {
    val config = parse(minimal)
    assertTrue(config.chat.enableDotCommands)
    assertTrue(config.chat.enableTagFailedNotifications)
    assertTrue(config.chat.dotCommandsWhitelist.isEmpty)
    assertTrue(config.chat.enableCommandsChannels.isEmpty)
    assertEquals(None, config.chat.itemDatabase)
    assertEquals(None, config.channels.head.fluxer.filters)
  }

  @Test
  def chatSettingsAreLowerCased(): Unit = {
    val config = parse(minimal + """chat { dot_commands_whitelist=["Server Info"], enable_commands_channels=["Bot-Commands"], enable_dot_commands=0 }""")
    assertEquals(Set("server info"), config.chat.dotCommandsWhitelist)
    assertEquals(Set("bot-commands"), config.chat.enableCommandsChannels)
    assertFalse(config.chat.enableDotCommands)
  }

  @Test
  def allDirectionsParse(): Unit = {
    Seq("both" -> ChatDirection.both, "wow_to_fluxer" -> ChatDirection.wow_to_fluxer, "fluxer_to_wow" -> ChatDirection.fluxer_to_wow)
      .foreach {
        case (value, expected) =>
          val config = parse(minimal.replace("direction=both", s"direction=$value"))
          assertEquals(expected, config.channels.head.chatDirection)
      }
  }

  private def assertFluxerRejected(fluxer: String, expectedMessagePart: String): Unit = {
    try {
      parseFluxer(fluxer)
      fail("expected the fluxer section to be rejected")
    } catch {
      case e: RuntimeException => assertTrue(e.getMessage, e.getMessage.contains(expectedMessagePart))
    }
  }

  @Test
  def fluxerSettings(): Unit = {
    val config = parseFluxer(s"""fluxer { token=" $FakeToken ", instance_url="https://fluxer.example.invalid" }""")
    assertEquals(FakeToken, config.fluxer.token)
    assertEquals(Some("https://fluxer.example.invalid"), config.fluxer.instanceUrl)
    assertEquals(None, config.fluxer.apiUrl)
    assertEquals(None, config.fluxer.gatewayUrl)
    assertFalse(config.toString.contains(FakeToken))
  }

  @Test
  def fluxerOverridesReplaceInstanceUrl(): Unit = {
    val config = parseFluxer(s"""fluxer { token="$FakeToken", api_url="https://api.example.invalid", gateway_url="wss://gw.example.invalid" }""")
    assertEquals(None, config.fluxer.instanceUrl)
    assertEquals(Some("https://api.example.invalid"), config.fluxer.apiUrl)
    assertEquals(Some("wss://gw.example.invalid"), config.fluxer.gatewayUrl)
  }

  @Test
  def fluxerSectionIsRequired(): Unit = {
    try {
      WowChatConfig.fromConfig(ConfigFactory.parseString(minimal)
        .withFallback(ConfigFactory.parseString("wow { account=a, password=b, character=c }")).resolve)
      fail("expected a missing fluxer section to be rejected")
    } catch {
      case e: IllegalArgumentException => assertTrue(e.getMessage.contains("\"fluxer\" configuration section is missing"))
    }
  }

  @Test
  def invalidFluxerSettingsAreRejected(): Unit = {
    assertFluxerRejected("""fluxer { instance_url="https://fluxer.example.invalid" }""", "FLUXER_TOKEN")
    // an unset ${?FLUXER_TOKEN} and a blank value are the same
    assertFluxerRejected("""fluxer { token=${?WOWCHAT_TEST_UNSET_VARIABLE}, instance_url="https://f.invalid" }""", "FLUXER_TOKEN")
    assertFluxerRejected("""fluxer { token="   ", instance_url="https://f.invalid" }""", "FLUXER_TOKEN")
    assertFluxerRejected(s"""fluxer { token="Bot $FakeToken", instance_url="https://f.invalid" }""", "without the \"Bot \" prefix")
    assertFluxerRejected(s"""fluxer { token="$FakeToken" }""", "FLUXER_INSTANCE_URL")
    assertFluxerRejected(s"""fluxer { token="$FakeToken", gateway_url="wss://gw.invalid" }""", "FLUXER_INSTANCE_URL")
    assertFluxerRejected(s"""fluxer { token="$FakeToken", instance_url="fluxer.example.invalid" }""", "fluxer.instance_url must be")
    assertFluxerRejected(s"""fluxer { token="$FakeToken", instance_url="https://f.invalid", gateway_url="https://gw.invalid" }""", "fluxer.gateway_url must be")
    assertFluxerRejected(s"""fluxer { token="$FakeToken", instance_url="https://f.invalid", api_url="wss://a.invalid" }""", "fluxer.api_url must be")
  }

  @Test
  def errorMessagesNeverContainTheToken(): Unit = {
    try {
      parseFluxer(s"""fluxer { token="$FakeToken", instance_url="not a url" }""")
      fail("expected rejection")
    } catch {
      case e: RuntimeException => assertFalse(e.getMessage.contains("FAKE-TEST-SECRET"))
    }
  }

  @Test
  def sampleConfigurationHasNoCredentials(): Unit = {
    val sample = ConfigFactory.parseFile(new File("src/main/resources/wowchat.conf")).resolve(
      com.typesafe.config.ConfigResolveOptions.defaults().setUseSystemEnvironment(false).setAllowUnresolved(true))
    assertFalse(sample.hasPath("fluxer.token"))
    assertFalse(sample.hasPath("fluxer.instance_url"))
    assertFalse(sample.hasPath("wow.account"))
    assertFalse(sample.hasPath("wow.password"))
  }

  @Test
  def oldDiscordSectionIsRejected(): Unit = {
    assertRejected(minimal + "discord { token=x }", "\"discord\" configuration section is no longer supported")
  }

  @Test
  def oldDiscordChannelBlockIsRejected(): Unit = {
    assertRejected(minimal.replace("fluxer {", "discord {"), "now use a \"fluxer\" block")
  }

  @Test
  def oldDirectionNamesAreRejected(): Unit = {
    assertRejected(minimal.replace("direction=both", "direction=wow_to_discord"), "Invalid chat direction \"wow_to_discord\"")
  }
}

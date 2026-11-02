package wowchat.common

import java.io.File

import com.typesafe.config.{Config, ConfigFactory}
import org.junit.Assert._
import org.junit.Test
import wowchat.TestSupport

class ConfigTest {

  // Make sure the expansion is initialized from test.conf before any config is parsed here.
  TestSupport.baseConfig

  // The sample reads credentials from environment variables; supply dummies.
  private val dummyWow = ConfigFactory.parseString("wow { account=a, password=b, character=c }")

  private def parse(hocon: String): WowChatConfig = {
    WowChatConfig.fromConfig(ConfigFactory.parseString(hocon).withFallback(dummyWow).resolve)
  }

  private val minimal =
    """wow { version=1.12.1, realmlist=realm.invalid, realm=r }
      |chat { channels=[ { direction=both, wow { type=Guild }, fluxer { channel=g, format="%message" } } ] }
      |""".stripMargin

  private def assertRejected(hocon: String, expectedMessagePart: String): Unit = {
    try {
      parse(hocon)
      fail("expected configuration to be rejected")
    } catch {
      case e: IllegalArgumentException => assertTrue(e.getMessage, e.getMessage.contains(expectedMessagePart))
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

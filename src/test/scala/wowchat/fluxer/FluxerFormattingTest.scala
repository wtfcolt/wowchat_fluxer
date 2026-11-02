package wowchat.fluxer

import org.junit.Assert._
import org.junit.Test
import wowchat.chat.ChatChannel

import scala.collection.mutable

class FluxerFormattingTest {

  // Realistic snowflakes: the plain @tag pass also scans the digits of an already resolved <@id>.
  private val Guild = "1500000000000000010"
  private val Officers = "1500000000000000011"
  private val Ada = "1500000000000000001"
  private val Bob = "1500000000000000002"
  private val Bot = "1500000000000000900"

  private val channel = ChatChannel("100", "guild-chat")

  private val snapshot = FluxerSnapshot(Some(Bot), Map(
    Guild -> CachedGuild(Guild, available = true,
      channels = Map("100" -> channel, "103" -> ChatChannel("103", "officers")),
      roles = Map(Guild -> "@everyone", Officers -> "Officers"),
      emojis = Seq(CachedEmoji("50", "pog", animated = false), CachedEmoji("51", "dance_party", animated = true)),
      members = Map(
        Ada -> CachedMember(Ada, "ada", Some("0042"), Some("Ada"), Some("Ada L.")),
        Bob -> CachedMember(Bob, "bobthebuilder", Some("0001"), None, None),
        Bot -> CachedMember(Bot, "wowchat", Some("0000"), Some("Ada Bot"), None)
      )),
    "20" -> CachedGuild("20", available = false, Map.empty, Map.empty, Seq(CachedEmoji("60", "hidden", animated = false)), Map.empty)
  ))

  private def mentions(message: String): (String, Seq[String]) = {
    val errors = mutable.ArrayBuffer.empty[String]
    (FluxerFormatting.resolveMentions(snapshot, channel, message, errors += _), errors)
  }

  // ---- escaping ----

  @Test
  def escapesMarkdownCharacters(): Unit = {
    assertEquals("\\*bold\\* \\_it\\_ \\~\\~strike\\~\\~ \\`code\\`", FluxerFormatting.escape("*bold* _it_ ~~strike~~ `code`"))
  }

  @Test
  def escapingKeepsMentionsEmojisAndLinkUrls(): Unit = {
    assertEquals("hi <@1> <@&11> <#100> <:dance_party:51> <a:dance_party:51> \\_x\\_",
      FluxerFormatting.escape("hi <@1> <@&11> <#100> <:dance_party:51> <a:dance_party:51> _x_"))
    assertEquals("[[Item\\_Name]](https://db.invalid/item_db?item=1) \\_",
      FluxerFormatting.escape("[[Item_Name]](https://db.invalid/item_db?item=1) _"))
  }

  @Test
  def escapingLeavesOtherAngleBracketsAlone(): Unit = {
    assertEquals("<\\_not a mention\\_>", FluxerFormatting.escape("<_not a mention_>"))
  }

  // ---- outgoing mentions ----

  @Test
  def resolvesDisplayNamesTagsAndRoles(): Unit = {
    assertEquals((s"hi <@$Ada>", Seq.empty), mentions("hi @Ada"))
    assertEquals((s"hi <@$Bob>", Seq.empty), mentions("hi @bobthe"))
    // a tag with characters other than letters, digits and _ needs quotes, as before
    assertEquals((s"<@$Ada> hi", Seq.empty), mentions("\"@ada#0042\" hi"))
    assertEquals((s"<@&$Officers> go", Seq.empty), mentions("@officers go"))
  }

  @Test
  def neverMentionsTheBotOrEveryone(): Unit = {
    // "Ada Bot" is the bot itself, so "@ada" matches only Ada L.
    assertEquals((s"<@$Ada>", Seq.empty), mentions("@ada"))
    assertEquals(("@everyone @here", Seq.empty), mentions("@everyone @here"))
  }

  @Test
  def ambiguousTagsReportErrors(): Unit = {
    // "d" is in both "Ada L." and "bobthebuilder"
    val (result, errors) = mentions("@d")
    assertEquals("@d", result)
    assertEquals(1, errors.size)
  }

  @Test
  def unknownChannelLeavesMessageUnchanged(): Unit = {
    val errors = mutable.ArrayBuffer.empty[String]
    assertEquals("@Ada", FluxerFormatting.resolveMentions(snapshot, ChatChannel("999", "x"), "@Ada", errors += _))
  }

  // ---- outgoing emojis ----

  @Test
  def resolvesCustomEmojiFromAvailableGuilds(): Unit = {
    assertEquals("<:pog:50> <a:Dance_Party:51> :hidden:", FluxerFormatting.resolveEmojis(snapshot, ":pog: :Dance_Party: :hidden:"))
  }

  @Test
  def formatLink(): Unit = {
    assertEquals("[[Sword]](https://db.invalid?item=1)", FluxerFormatting.formatLink("[Sword]", "https://db.invalid?item=1"))
  }

  // ---- incoming content ----

  @Test
  def rendersMentionsChannelsAndEmojis(): Unit = {
    val rendered = FluxerContentRenderer.render("hey <@1> <@!2> <@&11> see <#100> <:pog:50> <a:dance_party:51>",
      Map("1" -> "Ada L.", "2" -> "bob"), Map("11" -> "Officers"), Map("100" -> "guild-chat"))
    assertEquals("hey @Ada L. @bob @Officers see #guild-chat :pog: :dance_party:", rendered)
  }

  @Test
  def unknownMentionsAreLeftAsIs(): Unit = {
    assertEquals("<@77> <@&78> <#79> @everyone", FluxerContentRenderer.render("<@77> <@&78> <#79> @everyone", Map.empty, Map.empty, Map.empty))
  }

  @Test
  def renderingHandlesRegexSpecialCharactersInNames(): Unit = {
    assertEquals("@a$1\\b", FluxerContentRenderer.render("<@1>", Map("1" -> "a$1\\b"), Map.empty, Map.empty))
  }
}

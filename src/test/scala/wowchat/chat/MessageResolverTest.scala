package wowchat.chat

import org.junit.Assert._
import org.junit.{Before, Test}
import wowchat.TestSupport
import wowchat.common.Global

class MessageResolverTest {

  private val formatLink: (String, String) => String = (label, url) => s"[$label]($url)"

  @Before
  def setUp(): Unit = TestSupport.resetGlobals()

  @Test
  def vanillaLinks(): Unit = {
    val resolver = new MessageResolver(formatLink)
    assertEquals("[[Sword]](http://classicdb.ch?item=19019)",
      resolver.resolveLinks("|cffff8000|Hitem:19019:0:0:0|h[Sword]|h|r"))
    assertEquals("[[Fireball]](http://classicdb.ch?spell=133)",
      resolver.resolveLinks("|cff71d5ff|Hspell:133|h[Fireball]|h|r"))
    assertEquals("[[A Quest]](http://classicdb.ch?quest=42)",
      resolver.resolveLinks("|cffffff00|Hquest:42:10|h[A Quest]|h|r"))
  }

  @Test
  def multipleLinksInOneMessage(): Unit = {
    val resolver = new MessageResolver(formatLink)
    assertEquals("a [[One]](http://classicdb.ch?item=1) b [[Two]](http://classicdb.ch?item=2)",
      resolver.resolveLinks("a |cff1eff00|Hitem:1:0|h[One]|h|r b |cff1eff00|Hitem:2:0|h[Two]|h|r"))
  }

  @Test
  def defaultLinkSitePerExpansion(): Unit = {
    val item = "|cff1eff00|Hitem:1:0|h[One]|h|r"
    assertEquals("[[One]](http://tbc-twinhead.twinstar.cz?item=1)", new MessageResolverTBC(formatLink).resolveLinks(item))
    assertEquals("[[One]](http://wotlk-twinhead.twinstar.cz?item=1)", new MessageResolverWotLK(formatLink).resolveLinks(item))
    assertEquals("[[One]](https://cata-twinhead.twinstar.cz/?item=1)", new MessageResolverCataclysm(formatLink).resolveLinks(item))
    assertEquals("[[One]](http://mop-shoot.tauri.hu?item=1)", new MessageResolverMoP(formatLink).resolveLinks(item))
  }

  @Test
  def configuredItemDatabaseOverridesDefault(): Unit = {
    Global.config = Global.config.copy(chat = Global.config.chat.copy(itemDatabase = Some("https://db.invalid/")))
    assertEquals("[[One]](https://db.invalid/?item=1)",
      new MessageResolverWotLK(formatLink).resolveLinks("|cff1eff00|Hitem:1:0|h[One]|h|r"))
  }

  @Test
  def tbcTalentLinks(): Unit = {
    assertEquals("[[Talent]](http://tbc-twinhead.twinstar.cz?spell=5)",
      new MessageResolverTBC(formatLink).resolveLinks("|cff4e96f7|Htalent:5:1|h[Talent]|h|r"))
  }

  @Test
  def wotlkAchievementAndTradeLinks(): Unit = {
    val resolver = new MessageResolverWotLK(formatLink)
    assertEquals("[[Level 10]](http://wotlk-twinhead.twinstar.cz?achievement=6)",
      resolver.resolveLinks("|cffffff00|Hachievement:6:0000000000000001:1:1:1:9:0:0:0:0|h[Level 10]|h|r"))
    assertEquals("|cffffd000[[Tailoring]](http://wotlk-twinhead.twinstar.cz?spell=3908)|r",
      resolver.resolveLinks("|cffffd000|Htrade:3908:450:450:1:abc|h[Tailoring]|h|r"))
  }

  @Test
  def mopTradeLinks(): Unit = {
    assertEquals("|cffffd000[[Tailoring]](http://mop-shoot.tauri.hu?spell=3908)|r",
      new MessageResolverMoP(formatLink).resolveLinks("|cffffd000|Htrade:Player-1:3908:600:600|h[Tailoring]|h|r"))
  }

  @Test
  def resolveAchievementId(): Unit = {
    assertEquals("[[Level 10]](http://classicdb.ch?achievement=6)", new MessageResolver(formatLink).resolveAchievementId(6))
    assertEquals("[[999999]](http://classicdb.ch?achievement=999999)", new MessageResolver(formatLink).resolveAchievementId(999999))
  }

  @Test
  def stripColorCoding(): Unit = {
    val resolver = new MessageResolver(formatLink)
    assertEquals("red and plain", resolver.stripColorCoding("|cffff0000red|r and plain"))
    assertEquals("unterminated", resolver.stripColorCoding("|cffff0000unterminated"))
    assertEquals("costs 5$ now", resolver.stripColorCoding("|cffff0000costs 5$ now|r"))
  }

  @Test
  def stripColorCodingKeepsDollarOutsideColorCodes(): Unit = {
    assertEquals("costs 5$ now", new MessageResolver(formatLink).stripColorCoding("costs 5$ now"))
    assertEquals("a\\b c\\d", new MessageResolver(formatLink).stripColorCoding("a\\b |cffff0000c\\d|r"))
  }

  @Test
  def linksWithRegexSpecialCharacters(): Unit = {
    assertEquals("[[Ca$h \\1]](http://classicdb.ch?item=1)",
      new MessageResolver(formatLink).resolveLinks("|cff1eff00|Hitem:1:0|h[Ca$h \\1]|h|r"))
  }
}

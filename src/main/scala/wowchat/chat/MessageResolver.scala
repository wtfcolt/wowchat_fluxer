package wowchat.chat

import wowchat.common.{Global, WowChatConfig, WowExpansion}
import wowchat.game.GameResources

import scala.util.matching.Regex

// Converts WoW-specific markup (item/spell/quest links, color codes) in messages coming from WoW.
// formatLink(label, url) renders a link for the chat service. label is the WoW style "[Name]".
object MessageResolver {

  def apply(formatLink: (String, String) => String): MessageResolver = {
    WowChatConfig.getExpansion match {
      case WowExpansion.Vanilla => new MessageResolver(formatLink)
      case WowExpansion.TBC => new MessageResolverTBC(formatLink)
      case WowExpansion.WotLK => new MessageResolverWotLK(formatLink)
      case WowExpansion.Cataclysm => new MessageResolverCataclysm(formatLink)
      case WowExpansion.MoP => new MessageResolverMoP(formatLink)
    }
  }
}

class MessageResolver(formatLink: (String, String) => String) {

  protected val linkRegexes = Seq(
    "item" -> "\\|.+?\\|Hitem:(\\d+):.+?\\|h\\[(.+?)]\\|h\\|r".r,
    "spell" -> "\\|.+?\\|(?:Hspell|Henchant)?:(\\d+).*?\\|h\\[(.+?)]\\|h\\|r".r,
    "quest" -> "\\|.+?\\|Hquest:(\\d+):.+?\\|h\\[(.+?)]\\|h\\|r".r
  )

  protected val defaultLinkSite = "http://classicdb.ch"

  // lazy: subclasses override defaultLinkSite, which is not yet initialized while this constructor runs
  private lazy val linkSite = Global.config.chat.itemDatabase.fold(defaultLinkSite)(itemDatabase => {
    if (itemDatabase != "") {
      itemDatabase
    } else {
      defaultLinkSite
    }
  })

  def resolveLinks(message: String): String = {
    linkRegexes.foldLeft(message) {
      case (result, (classicDbKey, regex)) =>
        regex.replaceAllIn(result, m => {
          Regex.quoteReplacement(formatLink(s"[${m.group(2)}]", s"$linkSite?$classicDbKey=${m.group(1)}"))
        })
    }
  }

  def resolveAchievementId(achievementId: Int): String = {
    val name = GameResources.ACHIEVEMENT.getOrElse(achievementId, achievementId)
    formatLink(s"[$name]", s"$linkSite?achievement=$achievementId")
  }

  def stripColorCoding(message: String): String = {
    val hex = "\\|c[0-9a-fA-F]{8}"
    val pass1 = s"$hex(.*?)\\|r".r
    val pass2 = hex.r

    pass2.replaceAllIn(pass1.replaceAllIn(message, m => Regex.quoteReplacement(m.group(1))), "")
  }
}

// ADD MORE HERE AS NEEDED
class MessageResolverTBC(formatLink: (String, String) => String) extends MessageResolver(formatLink) {

  override protected val linkRegexes = Seq(
    "item" -> "\\|.+?\\|Hitem:(\\d+):.+?\\|h\\[(.+?)]\\|h\\|r".r,
    "spell" -> "\\|.+?\\|(?:Hspell|Henchant|Htalent)?:(\\d+).*?\\|h\\[(.+?)]\\|h\\|r".r,
    "quest" -> "\\|.+?\\|Hquest:(\\d+):.+?\\|h\\[(.+?)]\\|h\\|r".r
  )

  override protected val defaultLinkSite = "http://tbc-twinhead.twinstar.cz"
}

class MessageResolverWotLK(formatLink: (String, String) => String) extends MessageResolverTBC(formatLink) {

  override protected val linkRegexes = Seq(
    "item" -> "\\|.+?\\|Hitem:(\\d+):.+?\\|h\\[(.+?)]\\|h\\|r".r,
    "spell" -> "\\|.+?\\|(?:Hspell|Henchant|Htalent)?:(\\d+).*?\\|h\\[(.+?)]\\|h\\|r".r,
    "quest" -> "\\|.+?\\|Hquest:(\\d+):.+?\\|h\\[(.+?)]\\|h\\|r".r,
    "achievement" -> "\\|.+?\\|Hachievement:(\\d+):.+?\\|h\\[(.+?)]\\|h\\|r".r,
    "spell" -> "\\|Htrade:(\\d+):.+?\\|h\\[(.+?)]\\|h".r
  )

  override protected val defaultLinkSite = "http://wotlk-twinhead.twinstar.cz"
}

class MessageResolverCataclysm(formatLink: (String, String) => String) extends MessageResolverWotLK(formatLink) {

  override protected val defaultLinkSite = "https://cata-twinhead.twinstar.cz/"
}

class MessageResolverMoP(formatLink: (String, String) => String) extends MessageResolverCataclysm(formatLink) {

  override protected val linkRegexes = Seq(
    "item" -> "\\|.+?\\|Hitem:(\\d+):.+?\\|h\\[(.+?)]\\|h\\|r".r,
    "spell" -> "\\|.+?\\|(?:Hspell|Henchant|Htalent)?:(\\d+).*?\\|h\\[(.+?)]\\|h\\|r".r,
    "quest" -> "\\|.+?\\|Hquest:(\\d+):.+?\\|h\\[(.+?)]\\|h\\|r".r,
    "achievement" -> "\\|.+?\\|Hachievement:(\\d+):.+?\\|h\\[(.+?)]\\|h\\|r".r,
    "spell" -> "\\|Htrade:.+?:(\\d+):.+?\\|h\\[(.+?)]\\|h".r
  )

  override protected val defaultLinkSite = "http://mop-shoot.tauri.hu"
}

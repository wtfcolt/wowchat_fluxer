package wowchat.fluxer

import wowchat.chat.{ChatChannel, MentionResolver, TagCandidate}

import scala.util.matching.Regex

// Message markup that the Fluxer API documentation does not specify.
//
// UNVERIFIED: as of 2026-11-02 the Fluxer docs (docs.fluxer.com) do not document the in-text syntax of user, role
// and channel mentions or custom emoji. Message objects do carry parsed mentions/mention_roles arrays, so a syntax
// exists. The forms below are an assumption, kept in this one place so they are easy to correct after live testing.
// Incoming text that does not match them is passed through unchanged.
object FluxerSyntax {

  def userMention(userId: String): String = s"<@$userId>"

  def roleMention(roleId: String): String = s"<@&$roleId>"

  def customEmoji(name: String, id: String, animated: Boolean): String = {
    if (animated) s"<a:$name:$id>" else s"<:$name:$id>"
  }

  // <@id>, <@!id>, <@&id>, <#id>
  val MentionPattern: Regex = "<(@!?|@&|#)(\\d+)>".r
  // <:name:id>, <a:name:id>
  val CustomEmojiPattern: Regex = "<a?:(\\w+):(\\d+)>".r
}

object FluxerFormatting {

  // The characters escaped in messages from WoW, unchanged from the previous chat integration. Fluxer documents
  // backslash escaping ("A backslash before a symbol shows the symbol itself", https://fluxer.com/help/formatting-messages).
  private val EscapedCharacters = Set('`', '*', '_', '~')

  // Markup that must not be escaped: mentions, custom emoji and the URL part of a [label](url) link.
  private val ProtectedPattern: Regex = (
    FluxerSyntax.MentionPattern.regex + "|" +
    FluxerSyntax.CustomEmojiPattern.regex + "|" +
    "(?<=\\])\\(https?://[^\\s)]*\\)"
  ).r

  def escape(message: String): String = {
    val result = new StringBuilder
    var position = 0
    ProtectedPattern.findAllMatchIn(message).foreach(m => {
      result ++= escapePlain(message.substring(position, m.start))
      result ++= m.matched
      position = m.end
    })
    result ++= escapePlain(message.substring(position))
    result.toString
  }

  private def escapePlain(text: String): String = {
    text.flatMap(c => if (EscapedCharacters.contains(c)) s"\\$c" else c.toString)
  }

  def formatLink(label: String, url: String): String = s"[$label]($url)"

  // Resolves @tags typed in WoW against the guild of the channel the message goes to. Candidates are tried in
  // the same order as before: display names, then username#discriminator, then roles.
  def resolveMentions(snapshot: FluxerSnapshot, channel: ChatChannel, message: String, onError: String => Unit): String = {
    snapshot.guildForChannel(channel.id).fold(message)(guild => {
      val members = guild.members.values.toSeq
        .filterNot(member => snapshot.selfUserId.contains(member.userId))
        .sortBy(_.userId)
      val displayNames = members.map(m => TagCandidate(m.displayName, m.userId, isRole = false))
      val tags = members.map(m => TagCandidate(m.tag, m.userId, isRole = false))
      // The everyone role has the guild's id and is called @everyone.
      val roles = guild.roles.toSeq
        .filterNot { case (id, name) => id == guild.id || name == "@everyone" }
        .sortBy(_._1)
        .map { case (id, name) => TagCandidate(name, id, isRole = true) }

      MentionResolver.resolveTags(message, Seq(displayNames, tags, roles), candidate => {
        if (candidate.isRole) FluxerSyntax.roleMention(candidate.id) else FluxerSyntax.userMention(candidate.id)
      }, onError)
    })
  }

  // Resolves :name: to custom emoji from any guild the bot is in, as before.
  def resolveEmojis(snapshot: FluxerSnapshot, message: String): String = {
    val emojis = snapshot.guilds.values.filter(_.available).flatMap(_.emojis).toSeq.sortBy(_.id)
    val byName = emojis.reverse.map(e => e.name.toLowerCase -> e).toMap
    MentionResolver.resolveEmojis(message, byName.map { case (name, emoji) => name -> emoji.id }, (name, id) => {
      FluxerSyntax.customEmoji(name, id, byName.get(name.toLowerCase).exists(_.animated))
    })
  }
}

// Turns mention and emoji markup in incoming Fluxer messages into readable text for WoW.
object FluxerContentRenderer {

  // users: user id -> display name, roles: role id -> name, channels: channel id -> name.
  def render(content: String, users: Map[String, String], roles: Map[String, String], channels: Map[String, String]): String = {
    val withMentions = FluxerSyntax.MentionPattern.replaceAllIn(content, m => {
      val id = m.group(2)
      val replacement = m.group(1) match {
        case "@" | "@!" => users.get(id).map(name => s"@$name")
        case "@&" => roles.get(id).map(name => s"@$name")
        case "#" => channels.get(id).map(name => s"#$name")
        case _ => None
      }
      Regex.quoteReplacement(replacement.getOrElse(m.matched))
    })
    FluxerSyntax.CustomEmojiPattern.replaceAllIn(withMentions, m => Regex.quoteReplacement(s":${m.group(1)}:"))
  }
}

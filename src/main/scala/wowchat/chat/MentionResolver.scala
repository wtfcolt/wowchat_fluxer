package wowchat.chat

import scala.collection.mutable
import scala.util.matching.Regex

// A name that an @tag typed in WoW can resolve to.
case class TagCandidate(name: String, id: String, isRole: Boolean)

// Platform-neutral matching of @tags and :emoji: in WoW messages. The transport supplies the candidates
// and decides how a resolved mention or emoji is written.
object MentionResolver {

  // OR non-capturing regex didn't work for these for some reason
  private val tagRegexes = Seq("\"@(.+?)\"", "@([\\w]+)").map(_.r)

  // candidateGroups are tried in order (for example display names, then user names, then roles).
  def resolveTags(message: String,
                  candidateGroups: Seq[Seq[TagCandidate]],
                  formatMention: TagCandidate => String,
                  onError: String => Unit): String = {
    // each group
    tagRegexes.foldLeft(message) {
      case (result, regex) =>
        regex.replaceAllIn(result, m => {
          val tag = m.group(1)
          val matches = candidateGroups.foldLeft(Seq.empty[TagCandidate]) {
            case (result, candidates) =>
              val resolvedTags = resolveTagMatcher(candidates, tag)
              if (result.isEmpty) {
                resolvedTags
              } else if (result.size == 1) {
                // if one group hit a match, just use that as the best match.
                result
              } else {
                result ++ resolvedTags
              }
          }

          if (matches.size == 1) {
            Regex.quoteReplacement(formatMention(matches.head))
          } else if (matches.size > 1 && matches.size < 5) {
            onError(s"Your tag @$tag matches multiple channel members: ${
              matches.map(_.name).mkString(", ")
            }. Be more specific in your tag!")
            Regex.quoteReplacement(m.group(0))
          } else if (matches.size >= 5) {
            onError(s"Your tag @$tag matches too many channel members. Be more specific in your tag!")
            Regex.quoteReplacement(m.group(0))
          } else {
            Regex.quoteReplacement(m.group(0))
          }
        })
    }
  }

  private def resolveTagMatcher(candidates: Seq[TagCandidate], tag: String): Seq[TagCandidate] = {
    val lTag = tag.toLowerCase
    if (lTag == "here") {
      return Seq.empty
    }

    val matchesInitial = candidates
      .filter(_.name.toLowerCase.contains(lTag))

    if (matchesInitial.size > 1 && !lTag.contains(" ")) {
      // Multiple matches found. Prefer exact match first and a match where the tag is a whole word in the name second.
      matchesInitial.find(_.name.toLowerCase == lTag).fold({
        // Exact match not found. Try to find the tag as a whole word within the name.
        val namesWithMatchedWord = matchesInitial.filter(_.name.toLowerCase.split("\\W+").contains(lTag))
        if (namesWithMatchedWord.nonEmpty) {
          namesWithMatchedWord
        } else {
          matchesInitial
        }
      })(_ :: Nil)
    } else {
      matchesInitial
    }
  }

  // emojis maps lower case emoji name -> emoji id.
  def resolveEmojis(message: String, emojis: Map[String, String], formatEmoji: (String, String) => String): String = {
    val regex = "(?<=:).*?(?=:)".r

    val alreadyResolved = mutable.Set.empty[String]
    regex.findAllIn(message).foldLeft(message) {
      case (result, possibleEmoji) =>
        val lPossibleEmoji = possibleEmoji.toLowerCase
        if (alreadyResolved(lPossibleEmoji)) {
          result
        } else {
          emojis.get(lPossibleEmoji).fold(result)(id => {
            alreadyResolved += lPossibleEmoji
            result.replace(s":$possibleEmoji:", formatEmoji(possibleEmoji, id))
          })
        }
    }
  }
}

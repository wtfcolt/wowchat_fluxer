package wowchat.chat

import org.junit.Assert._
import org.junit.Test

import scala.collection.mutable

class MentionResolverTest {

  private val format: TagCandidate => String = c => if (c.isRole) s"<@&${c.id}>" else s"<@${c.id}>"

  private val displayNames = Seq(
    TagCandidate("Alice", "1", isRole = false),
    TagCandidate("Bob the Builder", "2", isRole = false),
    TagCandidate("Bobby", "3", isRole = false),
    TagCandidate("Carol", "4", isRole = false)
  )
  private val roles = Seq(TagCandidate("Officers", "10", isRole = true))

  private def resolve(message: String, groups: Seq[Seq[TagCandidate]] = Seq(displayNames, roles)): (String, Seq[String]) = {
    val errors = mutable.ArrayBuffer.empty[String]
    (MentionResolver.resolveTags(message, groups, format, errors += _), errors)
  }

  @Test
  def resolvesUniquePartialMatch(): Unit = {
    assertEquals(("hi <@1>", Seq.empty), resolve("hi @ali"))
  }

  @Test
  def resolvesRoles(): Unit = {
    assertEquals(("<@&10> help", Seq.empty), resolve("@officers help"))
  }

  @Test
  def quotedTagsMayContainSpaces(): Unit = {
    assertEquals(("<@2> hi", Seq.empty), resolve("\"@bob the builder\" hi"))
  }

  @Test
  def prefersExactMatch(): Unit = {
    val groups = Seq(Seq(TagCandidate("Bob", "5", isRole = false), TagCandidate("Bobby", "3", isRole = false)))
    assertEquals(("<@5>", Seq.empty), resolve("@bob", groups))
  }

  @Test
  def prefersWholeWordMatch(): Unit = {
    // "bob" is a whole word in "Bob the Builder" but only a prefix of "Bobby"
    assertEquals(("<@2>", Seq.empty), resolve("@bob"))
  }

  @Test
  def ambiguousTagsReportAnError(): Unit = {
    val groups = Seq(Seq(TagCandidate("Bobby", "3", isRole = false), TagCandidate("Bobbert", "6", isRole = false)))
    val (result, errors) = resolve("@bob", groups)
    assertEquals("@bob", result)
    assertEquals(Seq("Your tag @bob matches multiple channel members: Bobby, Bobbert. Be more specific in your tag!"), errors)
  }

  @Test
  def tooManyMatchesReportAnError(): Unit = {
    val groups = Seq((1 to 5).map(i => TagCandidate(s"Xname$i", i.toString, isRole = false)))
    val (result, errors) = resolve("@xname", groups)
    assertEquals("@xname", result)
    assertEquals(Seq("Your tag @xname matches too many channel members. Be more specific in your tag!"), errors)
  }

  @Test
  def firstGroupWithSingleMatchWins(): Unit = {
    val groups = Seq(Seq(TagCandidate("Dave", "7", isRole = false)), Seq(TagCandidate("Dave", "8", isRole = false)))
    assertEquals(("<@7>", Seq.empty), resolve("@dave", groups))
  }

  @Test
  def unknownTagsAndHereAreLeftAlone(): Unit = {
    assertEquals(("@zed @here", Seq.empty), resolve("@zed @here"))
  }

  @Test
  def tagsWithRegexSpecialCharactersAreLeftAlone(): Unit = {
    // no candidate contains "z", so the tag stays as typed (previously "$1" and "\" broke the regex replacement)
    assertEquals(("\"@z$1 \\q\" hi", Seq.empty), resolve("\"@z$1 \\q\" hi"))
  }

  @Test
  def resolvesEmojisCaseInsensitivelyOnce(): Unit = {
    val emojis = Map("pog" -> "11", "kekw" -> "12")
    assertEquals("<:Pog:11> <:Pog:11> <:kekw:12> :nope:",
      MentionResolver.resolveEmojis(":Pog: :Pog: :kekw: :nope:", emojis, (name, id) => s"<:$name:$id>"))
  }
}

package wowchat.fluxer

import org.junit.Assert._
import org.junit.{Before, Test}
import wowchat.chat.ChatChannel
import wowchat.fluxer.FluxerTestSupport._

class FluxerCacheTest {

  private var cache: FluxerCache = _

  private val guildCreate =
    """{"id":"10","properties":{"name":"Guild"},
      |"roles":[{"id":"10","name":"@everyone"},{"id":"11","name":"Officers"}],
      |"channels":[{"id":"100","type":0,"name":"guild-chat","guild_id":"10"},
      |            {"id":"101","type":2,"name":"voice"},
      |            {"id":"102","type":4,"name":"category"},
      |            {"id":"103","type":0,"name":"officers"}],
      |"emojis":[{"id":"50","name":"pog","animated":false},{"id":"51","name":"dance","animated":true}],
      |"members":[{"user":{"id":"900"},"nick":null,"roles":[]}],
      |"member_count":3,"online_count":1}""".stripMargin

  @Before
  def setUp(): Unit = {
    cache = new FluxerCache
    cache.reset(Some("900"), Seq("10", "20"))
  }

  private def apply(event: String, data: String): CacheUpdate = cache.applyDispatch(event, json(data))

  private def guild = cache.snapshot.guilds("10")

  @Test
  def readyListsGuildsAsUnavailable(): Unit = {
    assertEquals(Some("900"), cache.snapshot.selfUserId)
    assertEquals(Set("10", "20"), cache.snapshot.guilds.keySet)
    assertFalse(cache.snapshot.guilds("10").available)
    assertTrue(cache.snapshot.textChannels.isEmpty)
  }

  @Test
  def guildCreateLoadsTextChannelsRolesAndEmojis(): Unit = {
    val update = apply("GUILD_CREATE", guildCreate)
    assertTrue(update.textChannelsChanged)
    assertEquals(Some("10"), update.guildBecameAvailable)
    assertEquals(Seq(ChatChannel("100", "guild-chat"), ChatChannel("103", "officers")), cache.snapshot.textChannels)
    assertEquals(Map("10" -> "@everyone", "11" -> "Officers"), guild.roles)
    assertEquals(Seq(CachedEmoji("50", "pog", animated = false), CachedEmoji("51", "dance", animated = true)), guild.emojis)
    // a bot's Guild Create burst has members with only an id; they cannot be used for tags
    assertTrue(guild.members.isEmpty)
    assertEquals(Some(ChatChannel("100", "guild-chat")), cache.snapshot.channel("100"))
  }

  @Test
  def channelRenameCreateAndDelete(): Unit = {
    apply("GUILD_CREATE", guildCreate)
    assertTrue(apply("CHANNEL_UPDATE", """{"id":"100","type":0,"name":"renamed","guild_id":"10"}""").textChannelsChanged)
    assertEquals(Some("renamed"), cache.snapshot.channel("100").map(_.name))

    assertTrue(apply("CHANNEL_CREATE", """{"id":"104","type":0,"name":"new","guild_id":"10"}""").textChannelsChanged)
    assertTrue(apply("CHANNEL_DELETE", """{"id":"104","type":0,"name":"new","guild_id":"10"}""").textChannelsChanged)
    assertEquals(None, cache.snapshot.channel("104"))

    // a non-text channel never enters the list, and a text channel turned into another type leaves it
    assertFalse(apply("CHANNEL_CREATE", """{"id":"105","type":2,"name":"voice2","guild_id":"10"}""").textChannelsChanged)
    assertTrue(apply("CHANNEL_UPDATE", """{"id":"103","type":5,"name":"officers","guild_id":"10"}""").textChannelsChanged)
    assertEquals(None, cache.snapshot.channel("103"))

    // an update that changes nothing relevant
    assertFalse(apply("CHANNEL_UPDATE", """{"id":"100","type":0,"name":"renamed","guild_id":"10","topic":"x"}""").textChannelsChanged)
  }

  @Test
  def channelUpdateBulk(): Unit = {
    apply("GUILD_CREATE", guildCreate)
    apply("CHANNEL_UPDATE_BULK", """{"guild_id":"10","channels":[{"id":"100","type":0,"name":"a"},{"id":"103","type":0,"name":"b"}]}""")
    assertEquals(Seq("a", "b"), cache.snapshot.textChannels.map(_.name))
  }

  @Test
  def roles(): Unit = {
    apply("GUILD_CREATE", guildCreate)
    apply("GUILD_ROLE_CREATE", """{"guild_id":"10","role":{"id":"12","name":"Raiders"}}""")
    apply("GUILD_ROLE_UPDATE", """{"guild_id":"10","role":{"id":"11","name":"Council"}}""")
    apply("GUILD_ROLE_UPDATE_BULK", """{"guild_id":"10","roles":[{"id":"12","name":"Core"},{"id":"13","name":"Alts"}]}""")
    apply("GUILD_ROLE_DELETE", """{"guild_id":"10","role_id":"13"}""")
    assertEquals(Map("10" -> "@everyone", "11" -> "Council", "12" -> "Core"), guild.roles)
  }

  @Test
  def emojisAreReplaced(): Unit = {
    apply("GUILD_CREATE", guildCreate)
    apply("GUILD_EMOJIS_UPDATE", """{"guild_id":"10","emojis":[{"id":"52","name":"kekw","animated":false}]}""")
    assertEquals(Seq(CachedEmoji("52", "kekw", animated = false)), guild.emojis)
  }

  @Test
  def membersFromEventsAndHttp(): Unit = {
    apply("GUILD_CREATE", guildCreate)
    cache.upsertMembers("10", Seq(
      json("""{"user":{"id":"1","username":"ada","discriminator":"0042","global_name":"Ada"},"nick":"Ada L."}"""),
      json("""{"user":{"id":"2","username":"bob","discriminator":"0001","global_name":null},"nick":null}"""),
      json("""{"user":{"id":"3"}}""")
    ))
    assertEquals(Set("1", "2"), guild.members.keySet)
    assertEquals("Ada L.", guild.members("1").displayName)
    assertEquals("ada#0042", guild.members("1").tag)
    assertEquals("bob", guild.members("2").displayName)

    apply("GUILD_MEMBER_ADD", """{"guild_id":"10","user":{"id":"4","username":"cy","discriminator":"0000","global_name":"Cy"},"roles":[]}""")
    assertEquals("Cy", guild.members("4").displayName)

    // nickname cleared on a full update
    apply("GUILD_MEMBER_UPDATE", """{"guild_id":"10","user":{"id":"1","username":"ada","discriminator":"0042","global_name":"Ada"},"nick":null}""")
    assertEquals("Ada", guild.members("1").displayName)

    // an update with an id-only user keeps the known names and applies the nickname
    apply("GUILD_MEMBER_UPDATE", """{"guild_id":"10","user":{"id":"2"},"nick":"Bobby"}""")
    assertEquals("Bobby", guild.members("2").displayName)
    assertEquals("bob", guild.members("2").username)

    apply("GUILD_MEMBER_REMOVE", """{"guild_id":"10","user":{"id":"4"}}""")
    assertFalse(guild.members.contains("4"))

    apply("GUILD_MEMBERS_CHUNK", """{"guild_id":"10","members":[{"user":{"id":"5","username":"eve"}}],"chunk_index":0,"chunk_count":1}""")
    assertEquals("eve", guild.members("5").displayName)
  }

  @Test
  def messageCreateFillsMemberCache(): Unit = {
    apply("GUILD_CREATE", guildCreate)
    apply("MESSAGE_CREATE", """{"id":"1","channel_id":"100","guild_id":"10","channel_type":0,"type":0,"content":"hi",
      |"author":{"id":"7","username":"gwen","global_name":"Gwen"},"member":{"nick":"G","roles":[]}}""".stripMargin)
    assertEquals("G", guild.members("7").displayName)
  }

  @Test
  def laterGuildCreateKeepsKnownMembers(): Unit = {
    apply("GUILD_CREATE", guildCreate)
    cache.upsertMembers("10", Seq(json("""{"user":{"id":"1","username":"ada"}}""")))
    apply("GUILD_CREATE", guildCreate)
    assertTrue(guild.members.contains("1"))
  }

  @Test
  def guildDelete(): Unit = {
    apply("GUILD_CREATE", guildCreate)
    assertTrue(apply("GUILD_DELETE", """{"id":"10","unavailable":true}""").textChannelsChanged)
    assertTrue(cache.snapshot.guilds.contains("10"))
    assertTrue(cache.snapshot.textChannels.isEmpty)

    apply("GUILD_CREATE", guildCreate)
    apply("GUILD_DELETE", """{"id":"10"}""")
    assertFalse(cache.snapshot.guilds.contains("10"))
  }

  @Test
  def unavailableGuildCreate(): Unit = {
    val update = apply("GUILD_CREATE", """{"id":"20","unavailable":true}""")
    assertEquals(None, update.guildBecameAvailable)
    assertFalse(cache.snapshot.guilds("20").available)
  }

  @Test
  def malformedAndUnknownEventsAreIgnored(): Unit = {
    apply("GUILD_CREATE", guildCreate)
    val before = cache.snapshot
    Seq(
      "GUILD_CREATE" -> """{"name":"no id"}""",
      "GUILD_CREATE" -> "[]",
      "CHANNEL_CREATE" -> """{"id":"200","type":0,"name":"x"}""",            // no guild id
      "CHANNEL_CREATE" -> """{"id":"200","type":0,"name":"x","guild_id":"99"}""", // unknown guild
      "CHANNEL_CREATE" -> """{"id":"200","type":"0","name":"x","guild_id":"10"}""", // type as string
      "CHANNEL_UPDATE_BULK" -> """{"guild_id":"10","channels":"nope"}""",
      "GUILD_ROLE_CREATE" -> """{"guild_id":"10","role":"nope"}""",
      "GUILD_ROLE_DELETE" -> """{"role_id":"11"}""",
      "GUILD_EMOJIS_UPDATE" -> """{"emojis":[]}""",
      "GUILD_MEMBER_ADD" -> """{"guild_id":"10","user":"nope"}""",
      "GUILD_MEMBER_REMOVE" -> """{"guild_id":"10"}""",
      "MESSAGE_CREATE" -> """{"guild_id":"10","author":5}""",
      "SOMETHING_NEW" -> """{"guild_id":"10"}""",
      "GUILD_DELETE" -> "null"
    ).foreach {
      case (event, data) => assertFalse(s"$event $data", apply(event, data).textChannelsChanged)
    }
    assertEquals(before, cache.snapshot)
  }
}

package au.com.shiftyjelly.pocketcasts.musicassistant.player

import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode
import au.com.shiftyjelly.pocketcasts.musicassistant.FakeMaServer
import au.com.shiftyjelly.pocketcasts.musicassistant.FakeUpNext
import au.com.shiftyjelly.pocketcasts.musicassistant.InMemoryAccountStore
import au.com.shiftyjelly.pocketcasts.musicassistant.InMemoryPlaybackStore
import au.com.shiftyjelly.pocketcasts.musicassistant.RecordingFinisher
import au.com.shiftyjelly.pocketcasts.musicassistant.account
import au.com.shiftyjelly.pocketcasts.musicassistant.awaitValue
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaConnection
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaSession
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaSessionState
import au.com.shiftyjelly.pocketcasts.musicassistant.eventually
import au.com.shiftyjelly.pocketcasts.musicassistant.media.EpisodeSource
import au.com.shiftyjelly.pocketcasts.musicassistant.media.MaMediaResolver
import au.com.shiftyjelly.pocketcasts.musicassistant.mirror.MirroredItem
import au.com.shiftyjelly.pocketcasts.repositories.playback.Player
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlayerEvent
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class QueueMirroringTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    private val server = FakeMaServer().signedIn()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val session = MaSession(scope, InMemoryAccountStore(account(server.url)), { MaConnection(OkHttpClient()) })
    private val sources = listOf(
        EpisodeSource("a", FEED, guid = null, enclosureUrl = "https://cdn.example.com/a.mp3"),
        EpisodeSource("b", FEED, guid = null, enclosureUrl = "https://cdn.example.com/b.mp3?source=rss&amp=1"),
        EpisodeSource("c", FEED, guid = "has space", enclosureUrl = "https://cdn.example.com/c.mp3"),
    ).associateBy { it.uuid }
    private val resolver = MaMediaResolver(session, { uuids -> sources.filterKeys { it in uuids } }, { GPODDER })
    private val store = InMemoryPlaybackStore()
    private val upNext = FakeUpNext()
    private val finisher = RecordingFinisher()
    private val events = CopyOnWriteArrayList<PlayerEvent>()
    private val a = episode("a")
    private val b = episode("b")
    private val c = episode("c")

    @Before
    fun setUp() = runBlocking<Unit> {
        server.reply("music/podcasts/podcast_episodes", "podcast_episodes_partial", "podcast_episodes_final")
        server.reply("player_queues/all", "queues_all_idle")
        session.start()
        session.state.awaitValue { it is MaSessionState.Connected }
    }

    @After
    fun tearDown() {
        session.stop()
        scope.cancel()
        server.close()
    }

    @Test
    fun `hands the whole Up Next to the speaker`() = runBlocking<Unit> {
        upNext.episodes.value = listOf(a, b, c)
        server.reply("player_queues/items", "queue_items_three")
        val player = player()
        player.setEpisode(a)
        server.commands.clear()

        player.play(60_000)
        server.push("event_queue_updated_playing")

        assertEquals(mapOf("queue_id" to KITCHEN, "media" to listOf(URI_B, URI_C), "option" to "add"), secondPlayMedia().args)
        eventually { store.playback?.items == listOf(MIRROR_A, MIRROR_B, MIRROR_C) }
    }

    @Test
    fun `leaves out an Up Next episode that has no stream`() = runBlocking<Unit> {
        val local = PodcastEpisode(uuid = "local", publishedDate = Date(), downloadUrl = null)
        upNext.episodes.value = listOf(a, local, b)
        server.reply("player_queues/items", "queue_items")
        val player = player()
        player.setEpisode(a)
        server.commands.clear()

        player.play(60_000)
        server.push("event_queue_updated_playing")

        assertEquals(listOf(URI_B), secondPlayMedia().args["media"])
        eventually { store.playback?.items == listOf(MIRROR_A, MIRROR_B) }
    }

    @Test
    fun `hands at most 25 episodes to the speaker`() = runBlocking<Unit> {
        val many = (1..30).map { PodcastEpisode(uuid = "e$it", publishedDate = Date(), downloadUrl = "https://cdn.example.com/e$it.mp3") }
        upNext.episodes.value = many
        server.reply("player_queues/items", "queue_items")
        val player = player()
        player.setEpisode(many.first())
        server.commands.clear()

        player.play(0)
        server.push("event_queue_updated_playing")

        assertEquals((2..25).map { "builtin://track/https://cdn.example.com/e$it.mp3" }, secondPlayMedia().args["media"])
    }

    @Test
    fun `follows the speaker to the next episode`() = runBlocking<Unit> {
        handedOff(listOf(a, b, c), "queue_items_three")

        server.push("event_queue_updated_next_item")

        eventually { (events.lastOrNull() as? PlayerEvent.Completion)?.episodeUUID == "a" }
        assertEquals(listOf(MIRROR_B, MIRROR_C), store.playback?.items)
        assertTrue(finisher.finished.isEmpty())
    }

    @Test
    fun `finishes episodes the speaker played while nobody watched`() = runBlocking<Unit> {
        handedOff(listOf(a, b, c), "queue_items_three")

        server.push("event_queue_updated_third_item")

        eventually { (events.lastOrNull() as? PlayerEvent.Completion)?.episodeUUID == "a" }
        assertEquals(listOf("b"), finisher.finished.toList())
        assertEquals(listOf(MIRROR_C), store.playback?.items)
    }

    @Test
    fun `reports the end of the last mirrored episode`() = runBlocking<Unit> {
        val player = handedOff(listOf(a, b), "queue_items")
        server.push("event_queue_updated_next_item")
        eventually { (events.lastOrNull() as? PlayerEvent.Completion)?.episodeUUID == "a" }
        player.setEpisode(b)

        server.push("event_queue_updated_ended_after_next")

        eventually { (events.lastOrNull() as? PlayerEvent.Completion)?.episodeUUID == "b" }
    }

    @Test
    fun `finishes the last episode when the speaker ended past it unwatched`() = runBlocking<Unit> {
        handedOff(listOf(a, b), "queue_items")

        server.push("event_queue_updated_ended_after_next")

        eventually { (events.lastOrNull() as? PlayerEvent.Completion)?.episodeUUID == "a" }
        assertEquals(listOf("b"), finisher.finished.toList())
        assertNull(store.playback)
    }

    @Test
    fun `does not carry a pending seek to the next episode`() = runBlocking<Unit> {
        val player = handedOff(listOf(a, b), "queue_items")
        server.push("event_queue_updated_paused")
        eventually { events.lastOrNull() == PlayerEvent.PlayerPaused }
        server.reply("player_queues/seek", "error_invalid_command")
        player.seekToTimeMs(1_000_000)
        assertEquals(1_000_000, player.getCurrentPositionMs())

        server.pushEdited("event_queue_updated_next_item") { event -> queue(event)["state"] = "paused" }
        eventually { (events.lastOrNull() as? PlayerEvent.Completion)?.episodeUUID == "a" }
        player.setEpisode(b)
        server.commands.clear()

        assertEquals(-1, player.getCurrentPositionMs())
        player.play(0)
        assertEquals(FakeMaServer.Command("player_queues/play", mapOf("queue_id" to KITCHEN)), server.next())
    }

    @Test
    fun `does not resend an episode the speaker left out`() = runBlocking<Unit> {
        val player = handedOff(listOf(a, b, c), "queue_items", mirrored = listOf(MIRROR_A, MIRROR_B))

        player.play(60_000)

        assertNull(server.commands.firstOrNull { it.name == "player_queues/play_media" })
    }

    @Test
    fun `adds episodes appended to Up Next`() = runBlocking<Unit> {
        handedOff(listOf(a, b), "queue_items")
        server.reply("player_queues/items", "queue_items_three")

        upNext.episodes.value = listOf(a, b, c)

        val playMedia = server.nextExcept("music/podcasts/podcast_episodes")
        assertEquals(mapOf("queue_id" to KITCHEN, "media" to listOf(URI_C), "option" to "add"), playMedia.args)
        eventually { store.playback?.items == listOf(MIRROR_A, MIRROR_B, MIRROR_C) }
    }

    @Test
    fun `plays next an episode put after the current one`() = runBlocking<Unit> {
        handedOff(listOf(a, b), "queue_items")
        server.reply("player_queues/items", "queue_items_play_next")

        upNext.episodes.value = listOf(a, c, b)

        val playMedia = server.nextExcept("music/podcasts/podcast_episodes")
        assertEquals(mapOf("queue_id" to KITCHEN, "media" to listOf(URI_C), "option" to "next"), playMedia.args)
        eventually { store.playback?.items == listOf(MIRROR_A, MIRROR_C, MIRROR_B) }
    }

    @Test
    fun `deletes episodes removed from Up Next`() = runBlocking<Unit> {
        handedOff(listOf(a, b), "queue_items")
        server.reply("player_queues/items", "queue_items_current_only")

        upNext.episodes.value = listOf(a)

        assertEquals(mapOf("queue_id" to KITCHEN, "item_id_or_index" to ITEM_B), server.next().args)
        eventually { store.playback?.items == listOf(MIRROR_A) }
    }

    @Test
    fun `rewrites the upcoming items after a move`() = runBlocking<Unit> {
        handedOff(listOf(a, b, c), "queue_items_three")
        server.reply("player_queues/items", "queue_items_play_next")

        upNext.episodes.value = listOf(a, c, b)

        val playMedia = server.nextExcept("music/podcasts/podcast_episodes")
        assertEquals(mapOf("queue_id" to KITCHEN, "media" to listOf(URI_C, URI_B), "option" to "replace_next"), playMedia.args)
        eventually { store.playback?.items == listOf(MIRROR_A, MIRROR_C, MIRROR_B) }
    }

    @Test
    fun `leaves the queue alone while another episode takes over`() = runBlocking<Unit> {
        handedOff(listOf(a, b), "queue_items")

        upNext.episodes.value = listOf(c, a, b)
        Thread.sleep(600)

        assertTrue(server.commands.isEmpty())
    }

    private fun player() = MusicAssistantPlayer(
        remote = session,
        resolver = resolver,
        playbackStore = store,
        upNext = upNext,
        finisher = finisher,
        queueId = KITCHEN,
        playerName = "Kitchen",
        scope = scope,
        onPlayerEvent = { _: Player, event: PlayerEvent -> events += event },
    )

    private suspend fun handedOff(
        episodes: List<PodcastEpisode>,
        items: String,
        mirrored: List<MirroredItem> = listOf(MIRROR_A, MIRROR_B, MIRROR_C).take(episodes.size),
    ): MusicAssistantPlayer {
        upNext.episodes.value = episodes
        server.reply("player_queues/items", items)
        val player = player()
        player.setEpisode(episodes.first())
        player.play(60_000)
        server.push("event_queue_updated_playing")
        eventually { events.lastOrNull() == PlayerEvent.PlayerPlaying }
        eventually { store.playback?.items == mirrored }
        server.commands.clear()
        events.clear()
        return player
    }

    private fun secondPlayMedia(): FakeMaServer.Command {
        return generateSequence { server.next() }.filter { it.name == "player_queues/play_media" }.elementAt(1)
    }

    @Suppress("UNCHECKED_CAST")
    private fun queue(event: MutableMap<String, Any?>) = event["data"] as MutableMap<String, Any?>

    private fun episode(uuid: String) = PodcastEpisode(uuid = uuid, publishedDate = Date(), downloadUrl = sources.getValue(uuid).enclosureUrl)

    private companion object {
        const val KITCHEN = "media_player.kitchen"
        const val GPODDER = "gpodder--Xk3dTq9a"
        const val FEED = "https://pod.example.com/feeds/show.xml"
        const val ITEM_B = "0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f"
        const val URI_A = "$GPODDER://podcast_episode/$FEED https://pod.example.com/feeds/ep-a"
        const val URI_B = "$GPODDER://podcast_episode/$FEED urn:uuid:7a4f3c1e-0b2d-4e8a-9c5f-2d1e3b4a5c6d"
        const val URI_C = "$GPODDER://podcast_episode/$FEED https://cdn.example.com/c.mp3"
        val MIRROR_A = MirroredItem("a", "5b2f0e8c9d6a4f1b8e3c7a2d1f0e9b8a", URI_A)
        val MIRROR_B = MirroredItem("b", ITEM_B, URI_B)
        val MIRROR_C = MirroredItem("c", "9e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b", URI_C)
    }
}
